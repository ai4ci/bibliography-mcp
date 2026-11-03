package uk.co.terminological.bibliography.repository;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.crawl.CrawlJob;
import uk.co.terminological.bibliography.crawl.Direction;
import uk.co.terminological.bibliography.crawl.JobStatus;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableAuthor;
import uk.co.terminological.bibliography.record.ImmutableRecord;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.MergedRecord;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * The repository in a SQLite file.
 *
 * Chosen over a JSON-lines log with an in-memory index after measuring both (plan-010): both are fast, but the log's
 * index costs around 550 bytes per record <em>resident</em> — 274 MB at half a million records — where this answers a
 * top-N query from a cold reader while the crawl that is filling it still holds the file open. The write cost it pays
 * for that, three seconds per half-million records, is nothing beside the hours of API calls that produced them.
 *
 * <h2>Two tables for one work</h2>
 *
 * {@code contribution} holds what each source said; {@code record} holds the merged view of it. Queries read
 * {@code record}, which is why they need no joins and no merging; {@link #putRecord} re-merges from the contributions,
 * which is why a third source merges with the first two rather than into their already-merged result.
 *
 * <h2>Durability</h2>
 *
 * WAL journalling, so a reader and the crawl can hold the file at once, and {@code synchronous=NORMAL}, which risks
 * the last transaction to a power failure rather than every transaction to a crash. A crawl checkpoints as it goes, so
 * what that loses is at most the last record written.
 */
// [IMPLEMENTS](/design/features/feat-010-crawl-engine-with-zotero-mirror.md)
public class SqliteRepositoryStore implements RepositoryStore {

	/**
	 * The schema this build writes and the only one it reads.
	 *
	 * Bump it when the tables change, and a repository written by a later build is refused by name rather than
	 * misread — which is the whole of what debt-001's {@code InvalidClassException} could not do.
	 */
	public static final int SCHEMA_VERSION = 1;

	private final Connection connection;
	private final Path file;
	private final RepositoryLock lock;

	private SqliteRepositoryStore(
			Connection connection, Path file, RepositoryLock lock
	) {
		this.connection = connection;
		this.file = file;
		this.lock = lock;
	}

	/** Open for reading, without claiming the repository. */
	public static SqliteRepositoryStore open(Path file) {
		return open(file, false);
	}

	/**
	 * Open, creating the repository when the file does not exist yet.
	 *
	 * @param exclusive claim the repository for writing. A server does; a reader does not. SQLite's WAL already makes
	 * concurrent readers safe, and says nothing about two crawl engines racing on the same records and spending two
	 * API budgets at once — which is what this refuses.
	 */
	public static SqliteRepositoryStore open(Path file, boolean exclusive) {
		RepositoryLock lock = null;
		try {
			var parent = file.toAbsolutePath()
				.getParent();
			if (parent != null) java.nio.file.Files.createDirectories(parent);
			var connection = DriverManager
				.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
			try (Statement s = connection.createStatement()) {
				s.execute("PRAGMA journal_mode=WAL");
				s.execute("PRAGMA synchronous=NORMAL");
				s.execute("PRAGMA foreign_keys=ON");
				s.execute("PRAGMA busy_timeout=5000");
			}
			if (exclusive) lock = RepositoryLock.claim(file);
			var store = new SqliteRepositoryStore(connection, file, lock);
			store.createOrCheckSchema();
			return store;
		} catch (SQLException | java.io.IOException e) {
			if (lock != null) lock.release();
			throw new RepositoryException(
					"could not open the repository at " + file, e
			);
		} catch (RepositoryException e) {
			if (lock != null) lock.release();
			throw e;
		}
	}

	private void createOrCheckSchema() throws SQLException {
		try (Statement s = this.connection.createStatement()) {
			s.execute(
				"CREATE TABLE IF NOT EXISTS meta ("
						+ "schemaVersion INTEGER NOT NULL, createdAt TEXT NOT NULL)"
			);
			try (ResultSet rs = s
				.executeQuery("SELECT schemaVersion FROM meta LIMIT 1")) {
				if (rs.next()) {
					int found = rs.getInt(1);
					if (found != SCHEMA_VERSION) throw new RepositoryException(
							"this repository was written with schema version " + found
									+ ", and this build reads schema version "
									+ SCHEMA_VERSION + ": " + this.file
					);
					return;
				}
			}
			s.execute(
				"INSERT INTO meta (schemaVersion, createdAt) VALUES ("
						+ SCHEMA_VERSION + ", '" + Instant.now() + "')"
			);
			// the merged view: one row per work, and what every query reads
			s.execute(
				"CREATE TABLE IF NOT EXISTS record ("
						+ "idType TEXT NOT NULL, id TEXT NOT NULL,"
						+ " title TEXT, abstract TEXT, journal TEXT, date TEXT,"
						+ " publicationType TEXT, publisher TEXT, pdfUri TEXT,"
						+ " score REAL, selected INTEGER NOT NULL DEFAULT 0,"
						+ " PRIMARY KEY (idType, id))"
			);
			// what each source said, kept so that a later claim re-merges with the
			// others rather than into their merged result
			s.execute(
				"CREATE TABLE IF NOT EXISTS contribution ("
						+ "idType TEXT NOT NULL, id TEXT NOT NULL, source TEXT NOT NULL,"
						+ " nativeType TEXT, curated INTEGER NOT NULL DEFAULT 0,"
						+ " title TEXT, abstract TEXT, journal TEXT, date TEXT,"
						+ " publicationType TEXT, publisher TEXT, pdfUri TEXT,"
						+ " PRIMARY KEY (idType, id, source))"
			);
			s.execute(
				"CREATE TABLE IF NOT EXISTS author ("
						+ "idType TEXT NOT NULL, id TEXT NOT NULL, source TEXT NOT NULL,"
						+ " position INTEGER NOT NULL, lastName TEXT NOT NULL,"
						+ " firstName TEXT, initials TEXT, orcid TEXT,"
						+ " PRIMARY KEY (idType, id, source, position))"
			);
			s.execute(
				"CREATE TABLE IF NOT EXISTS identifier ("
						+ "idType TEXT NOT NULL, id TEXT NOT NULL,"
						+ " otherIdType TEXT NOT NULL, otherId TEXT NOT NULL,"
						+ " PRIMARY KEY (idType, id, otherIdType, otherId))"
			);
			s.execute(
				"CREATE TABLE IF NOT EXISTS citation ("
						+ "srcIdType TEXT NOT NULL, srcId TEXT NOT NULL,"
						+ " tgtIdType TEXT NOT NULL, tgtId TEXT NOT NULL,"
						+ " idx INTEGER, srcTitle TEXT, tgtTitle TEXT,"
						+ " PRIMARY KEY (srcIdType, srcId, tgtIdType, tgtId))"
			);
			// a crawl job and its frontier. The nodes are rows rather than a blob so
			// that a frontier of 10^5 is streamed back rather than parsed whole
			s.execute(
				"CREATE TABLE IF NOT EXISTS job ("
						+ "id TEXT PRIMARY KEY, direction TEXT NOT NULL, depth INTEGER NOT NULL,"
						+ " maxRecords INTEGER NOT NULL, status TEXT NOT NULL,"
						+ " errors INTEGER NOT NULL DEFAULT 0, stoppedBy TEXT,"
						+ " startedAt TEXT, finishedAt TEXT, callsPerApi TEXT)"
			);
			s.execute(
				"CREATE TABLE IF NOT EXISTS job_node ("
						+ "jobId TEXT NOT NULL, idType TEXT NOT NULL, id TEXT NOT NULL,"
						+ " depth INTEGER NOT NULL, seed INTEGER NOT NULL DEFAULT 0,"
						+ " visited INTEGER NOT NULL DEFAULT 0,"
						+ " queued INTEGER NOT NULL DEFAULT 0, position INTEGER,"
						+ " PRIMARY KEY (jobId, idType, id))"
			);
			s.execute("CREATE INDEX IF NOT EXISTS idx_score ON record (score DESC)");
			s.execute(
				"CREATE INDEX IF NOT EXISTS idx_selected ON record (selected)"
			);
			s.execute(
				"CREATE INDEX IF NOT EXISTS idx_citation_tgt ON citation (tgtIdType, tgtId)"
			);
		}
	}

	/** Visible for testing: write a schema version this build does not know, to prove the refusal names both. */
	public static void setSchemaVersionForTest(Path file, int version) {
		try (var c = DriverManager
			.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
				Statement s = c.createStatement()) {
			s.executeUpdate("UPDATE meta SET schemaVersion = " + version);
		} catch (SQLException e) {
			throw new RepositoryException("could not rewrite the schema version", e);
		}
	}

	@Override
	public RepositoryMeta meta() {
		try (Statement s = this.connection.createStatement();
				ResultSet rs = s
					.executeQuery("SELECT schemaVersion, createdAt FROM meta LIMIT 1")) {
			if (!rs.next()) throw new RepositoryException(
					"the repository has no meta row: " + this.file
			);
			return new RepositoryMeta(
					rs.getInt(1), Instant.parse(rs.getString(2))
			);
		} catch (SQLException e) {
			throw new RepositoryException("could not read the repository meta", e);
		}
	}

	// ---- writing ------------------------------------------------------------

	@Override
	public void putRecord(Record record) {
		var key = keyOf(record);
		try {
			this.connection.setAutoCommit(false);
			writeContribution(key, record);
			writeAuthors(key, record);
			writeIdentifiers(key, record);
			materialise(key);
			this.connection.commit();
		} catch (SQLException e) {
			rollback();
			throw new RepositoryException(
					"could not store a record for " + key.getIdentifier()
						.orElse("an unidentified work"),
					e
			);
		} finally {
			autoCommit();
		}
	}

	private void writeContribution(ImmutableRecordReference key, Record record)
			throws SQLException {
		try (PreparedStatement p = this.connection.prepareStatement(
			"INSERT OR REPLACE INTO contribution (idType, id, source, nativeType,"
					+ " curated, title, abstract, journal, date, publicationType,"
					+ " publisher, pdfUri) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
		)) {
			var provenance = record.getProvenance();
			p.setString(1, key.getIdentifierType()
				.name());
			p.setString(2, key.getIdentifier()
				.orElse(""));
			p.setString(3, provenance.getSource());
			p.setString(
					4, provenance.getNativeType()
						.orElse(null)
			);
			p.setInt(5, provenance.isCurated() ? 1 : 0);
			p.setString(
					6, record.getTitle()
						.orElse(null)
			);
			p.setString(
					7, record.getAbstract()
						.orElse(null)
			);
			p.setString(
					8, record.getJournal()
						.orElse(null)
			);
			p.setString(
					9, record.getDate()
						.map(LocalDate::toString)
						.orElse(null)
			);
			p.setString(
					10, record.getPublicationType()
						.orElse(null)
			);
			p.setString(
					11, record.getPublisher()
						.orElse(null)
			);
			p.setString(
					12, record.getPdfUri()
						.map(URI::toString)
						.orElse(null)
			);
			p.executeUpdate();
		}
	}

	private void writeAuthors(ImmutableRecordReference key, Record record)
			throws SQLException {
		try (PreparedStatement delete = this.connection.prepareStatement(
			"DELETE FROM author WHERE idType = ? AND id = ? AND source = ?"
		)) {
			delete.setString(1, key.getIdentifierType()
				.name());
			delete.setString(2, key.getIdentifier()
				.orElse(""));
			delete.setString(
					3, record.getProvenance()
						.getSource()
			);
			delete.executeUpdate();
		}
		if (record.getAuthors()
			.isEmpty()) return;
		try (PreparedStatement p = this.connection.prepareStatement(
			"INSERT OR REPLACE INTO author (idType, id, source, position, lastName,"
					+ " firstName, initials, orcid) VALUES (?,?,?,?,?,?,?,?)"
		)) {
			var authors = record.getAuthors();
			for (int i = 0; i < authors.size(); i++) {
				var author = authors.get(i);
				p.setString(1, key.getIdentifierType()
					.name());
				p.setString(2, key.getIdentifier()
					.orElse(""));
				p.setString(
						3, record.getProvenance()
							.getSource()
				);
				p.setInt(4, i);
				p.setString(5, author.getLastName());
				p.setString(
						6, author.getFirstName()
							.orElse(null)
				);
				p.setString(
						7, author.getInitials()
							.orElse(null)
				);
				p.setString(
						8, author.getORCID()
							.orElse(null)
				);
				p.addBatch();
			}
			p.executeBatch();
		}
	}

	private void writeIdentifiers(ImmutableRecordReference key, Record record)
			throws SQLException {
		if (record.getOtherIdentifiers()
			.isEmpty()) return;
		try (PreparedStatement p = this.connection.prepareStatement(
			"INSERT OR IGNORE INTO identifier (idType, id, otherIdType, otherId)"
					+ " VALUES (?,?,?,?)"
		)) {
			for (RecordReference other : record.getOtherIdentifiers()) {
				if (other.getIdentifier()
					.isEmpty()) continue;
				p.setString(1, key.getIdentifierType()
					.name());
				p.setString(2, key.getIdentifier()
					.orElse(""));
				p.setString(3, other.getIdentifierType()
					.name());
				p.setString(
						4, other.getIdentifier()
							.get()
				);
				p.addBatch();
			}
			p.executeBatch();
		}
	}

	/** Re-merge the contributions and write the merged view that queries read. */
	private void materialise(ImmutableRecordReference key) throws SQLException {
		var merged = mergeContributions(key);
		if (merged.isEmpty()) return;
		var record = merged.get();
		try (PreparedStatement p = this.connection.prepareStatement(
			"INSERT INTO record (idType, id, title, abstract, journal, date,"
					+ " publicationType, publisher, pdfUri) VALUES (?,?,?,?,?,?,?,?,?)"
					+ " ON CONFLICT (idType, id) DO UPDATE SET title=excluded.title,"
					+ " abstract=excluded.abstract, journal=excluded.journal,"
					+ " date=excluded.date, publicationType=excluded.publicationType,"
					+ " publisher=excluded.publisher, pdfUri=excluded.pdfUri"
		)) {
			p.setString(1, key.getIdentifierType()
				.name());
			p.setString(2, key.getIdentifier()
				.orElse(""));
			p.setString(
					3, record.getTitle()
						.orElse(null)
			);
			p.setString(
					4, record.getAbstract()
						.orElse(null)
			);
			p.setString(
					5, record.getJournal()
						.orElse(null)
			);
			p.setString(
					6, record.getDate()
						.map(LocalDate::toString)
						.orElse(null)
			);
			p.setString(
					7, record.getPublicationType()
						.orElse(null)
			);
			p.setString(
					8, record.getPublisher()
						.orElse(null)
			);
			p.setString(
					9, record.getPdfUri()
						.map(URI::toString)
						.orElse(null)
			);
			p.executeUpdate();
		}
	}

	@Override
	public void putCitation(CitationLink link) {
		var source = endpoint(link.getSource());
		var target = endpoint(link.getTarget());
		if (source.isEmpty() || target.isEmpty()) return;
		try (PreparedStatement p = this.connection.prepareStatement(
			"INSERT OR REPLACE INTO citation (srcIdType, srcId, tgtIdType, tgtId,"
					+ " idx, srcTitle, tgtTitle) VALUES (?,?,?,?,?,?,?)"
		)) {
			p.setString(1, source.get()
				.getIdentifierType()
				.name());
			p.setString(
					2, source.get()
						.getIdentifier()
						.orElse("")
			);
			p.setString(3, target.get()
				.getIdentifierType()
				.name());
			p.setString(
					4, target.get()
						.getIdentifier()
						.orElse("")
			);
			var index = link.getIndex();
			if (index.isPresent()) p.setInt(5, index.get());
			else p.setNull(5, java.sql.Types.INTEGER);
			p.setString(
					6, link.getSource()
						.getTitle()
						.orElse(null)
			);
			p.setString(
					7, link.getTarget()
						.getTitle()
						.orElse(null)
			);
			p.executeUpdate();
		} catch (SQLException e) {
			throw new RepositoryException("could not store a citation", e);
		}
	}

	@Override
	public void setScore(RecordReference ref, double score) {
		update(
			"UPDATE record SET score = ? WHERE idType = ? AND id = ?", ref,
			p -> p.setDouble(1, score)
		);
	}

	@Override
	public java.util.List<java.util.Map.Entry<Record, Long>> topByCitedBy(
			int limit
	) {
		java.util.List<java.util.Map.Entry<Record, Long>> out = new ArrayList<>();
		// grouped in the database rather than in memory: the whole point of the store
		// is that "the most cited hundred" does not mean reading the edges
		var sql = "SELECT tgtIdType, tgtId, count(*) AS n FROM citation"
				+ " GROUP BY tgtIdType, tgtId ORDER BY n DESC LIMIT " + limit;
		try (Statement s = this.connection.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				var ref = Builder.recordReference(
					IdType.valueOf(rs.getString(1)), rs.getString(2)
				);
				long count = rs.getLong(3);
				// a cited work the crawl never fetched has no record; report what is
				// known of it rather than dropping it from the ranking
				var record = getRecord(ref);
				out.add(
					new java.util.AbstractMap.SimpleEntry<>(
							record.orElseGet(() -> placeholderFor(ref)), count
					)
				);
			}
		} catch (SQLException e) {
			throw new RepositoryException("could not rank by citation count", e);
		}
		return out;
	}

	/**
	 * A work the repository knows only as the end of an edge.
	 *
	 * Half the references in a reference list resolve to no record any API will return, so a ranking that dropped
	 * them would quietly under-report the most-cited works in the corpus.
	 */
	private static Record placeholderFor(ImmutableRecordReference ref) {
		return ImmutableRecord.builder()
			.identifierType(ref.getIdentifierType())
			.identifier(
				ref.getIdentifier()
					.orElse(null)
			)
			.provenance(Provenance.placeholder("citation"))
			.licenses(Stream.empty())
			.build();
	}

	@Override
	public void markSelected(RecordReference ref, boolean selected) {
		update(
			"UPDATE record SET selected = ? WHERE idType = ? AND id = ?", ref,
			p -> p.setInt(1, selected ? 1 : 0)
		);
	}

	private interface Binder {
		void bind(PreparedStatement p) throws SQLException;
	}

	private void update(String sql, RecordReference reference, Binder first) {
		var ref = canonical(reference);
		try (PreparedStatement p = this.connection.prepareStatement(sql)) {
			first.bind(p);
			p.setString(2, ref.getIdentifierType()
				.name());
			p.setString(
					3, ref.getIdentifier()
						.orElse("")
			);
			p.executeUpdate();
		} catch (SQLException e) {
			throw new RepositoryException("could not update a record", e);
		}
	}

	// ---- reading ------------------------------------------------------------

	@Override
	public Optional<Record> getRecord(RecordReference ref) {
		return mergeContributions(ref.asId())
			.map(r -> (Record) r);
	}

	/**
	 * The identifier a work is actually stored under.
	 *
	 * A record is stored under the identifier it was fetched by, while a citation edge names the same work by
	 * whichever identifier the citing source used — a DOI here, an OpenAlex id there. The store already records those
	 * equivalences; this is where it uses them. Without it the most-cited works in a crawl come back as anonymous
	 * placeholders, because the edge and the record name the same paper differently.
	 */
	private ImmutableRecordReference canonical(RecordReference ref) {
		var asked = ref.asId();
		try (PreparedStatement p = this.connection.prepareStatement(
			"SELECT 1 FROM contribution WHERE idType = ? AND id = ? LIMIT 1"
		)) {
			p.setString(1, asked.getIdentifierType()
				.name());
			p.setString(
					2, asked.getIdentifier()
						.orElse("")
			);
			try (ResultSet rs = p.executeQuery()) {
				if (rs.next()) return asked;
			}
		} catch (SQLException e) {
			throw new RepositoryException("could not resolve an identifier", e);
		}
		try (PreparedStatement p = this.connection.prepareStatement(
			"SELECT idType, id FROM identifier WHERE otherIdType = ? AND otherId = ?"
					+ " LIMIT 1"
		)) {
			p.setString(1, asked.getIdentifierType()
				.name());
			p.setString(
					2, asked.getIdentifier()
						.orElse("")
			);
			try (ResultSet rs = p.executeQuery()) {
				if (rs.next()) return Builder.recordReference(
						IdType.valueOf(rs.getString(1)), rs.getString(2)
				);
			}
		} catch (SQLException e) {
			throw new RepositoryException("could not resolve an identifier", e);
		}
		return asked;
	}

	@Override
	public Collection<Record> contributions(RecordReference reference) {
		var ref = canonical(reference);
		List<Record> out = new ArrayList<>();
		try (PreparedStatement p = this.connection.prepareStatement(
			"SELECT source, nativeType, curated, title, abstract, journal, date,"
					+ " publicationType, publisher, pdfUri FROM contribution"
					+ " WHERE idType = ? AND id = ?"
		)) {
			p.setString(1, ref.getIdentifierType()
				.name());
			p.setString(
					2, ref.getIdentifier()
						.orElse("")
			);
			try (ResultSet rs = p.executeQuery()) {
				while (rs.next()) {
					out.add(contributionOf(ref.asId(), rs));
				}
			}
		} catch (SQLException e) {
			throw new RepositoryException("could not read the contributions", e);
		}
		return out;
	}

	private Optional<MergedRecord> mergeContributions(RecordReference ref) {
		MergedRecord merged = null;
		for (Record contribution : contributions(ref)) {
			merged = merged == null ? MergedRecord.from(contribution)
				: merged.merge(contribution);
		}
		return Optional.ofNullable(merged);
	}

	private Record contributionOf(ImmutableRecordReference key, ResultSet rs)
			throws SQLException {
		var source = rs.getString("source");
		var nativeType = rs.getString("nativeType");
		var provenance = rs.getInt("curated") == 1 ? Provenance.curated(source)
			: nativeType == null ? Provenance.placeholder(source)
				: Provenance.of(source, nativeType);
		var builder = ImmutableRecord.builder()
			.identifierType(key.getIdentifierType())
			.identifier(
				key.getIdentifier()
					.orElse(null)
			)
			.provenance(provenance)
			.licenses(Stream.empty())
			.title(Optional.ofNullable(rs.getString("title")))
			.getAbstract(Optional.ofNullable(rs.getString("abstract")))
			.journal(Optional.ofNullable(rs.getString("journal")))
			.publicationType(
				Optional.ofNullable(rs.getString("publicationType"))
			)
			.publisher(Optional.ofNullable(rs.getString("publisher")));
		var date = rs.getString("date");
		if (date != null) builder.date(LocalDate.parse(date));
		var pdf = rs.getString("pdfUri");
		if (pdf != null) builder.pdfUri(URI.create(pdf));
		builder.authors(authorsOf(key, source));
		builder.otherIdentifiers(identifiersOf(key));
		return builder.build();
	}

	private List<Author> authorsOf(ImmutableRecordReference key, String source) {
		List<Author> out = new ArrayList<>();
		try (PreparedStatement p = this.connection.prepareStatement(
			"SELECT lastName, firstName, initials, orcid FROM author"
					+ " WHERE idType = ? AND id = ? AND source = ? ORDER BY position"
		)) {
			p.setString(1, key.getIdentifierType()
				.name());
			p.setString(
					2, key.getIdentifier()
						.orElse("")
			);
			p.setString(3, source);
			try (ResultSet rs = p.executeQuery()) {
				while (rs.next()) {
					out.add(
						ImmutableAuthor.builder()
							.lastName(rs.getString("lastName"))
							.firstName(
								Optional.ofNullable(rs.getString("firstName"))
							)
							.initials(Optional.ofNullable(rs.getString("initials")))
							.oRCID(Optional.ofNullable(rs.getString("orcid")))
							.affiliations(Stream.empty())
							.build()
					);
				}
			}
		} catch (SQLException e) {
			throw new RepositoryException("could not read the authors", e);
		}
		return out;
	}

	private List<RecordReference> identifiersOf(ImmutableRecordReference key) {
		List<RecordReference> out = new ArrayList<>();
		try (PreparedStatement p = this.connection.prepareStatement(
			"SELECT otherIdType, otherId FROM identifier WHERE idType = ? AND id = ?"
		)) {
			p.setString(1, key.getIdentifierType()
				.name());
			p.setString(
					2, key.getIdentifier()
						.orElse("")
			);
			try (ResultSet rs = p.executeQuery()) {
				while (rs.next()) {
					out.add(
						Builder.recordReference(
							IdType.valueOf(rs.getString("otherIdType")),
							rs.getString("otherId")
						)
					);
				}
			}
		} catch (SQLException e) {
			throw new RepositoryException("could not read the identifiers", e);
		}
		return out;
	}

	@Override
	public Stream<Record> records() { return hydrate(keys("1 = 1", null)); }

	@Override
	public Stream<Record> selected() {
		return hydrate(keys("selected = 1", null));
	}

	@Override
	public Stream<Record> topByScore(int limit) {
		return hydrate(
			keys("score IS NOT NULL", "ORDER BY score DESC LIMIT " + limit)
		);
	}

	/**
	 * The identifiers a query selects, read from the materialised view.
	 *
	 * The keys come back from an index scan and the records are hydrated one at a time, so "the top hundred" costs a
	 * hundred hydrations rather than a repository.
	 */
	private List<ImmutableRecordReference> keys(String where, String order) {
		List<ImmutableRecordReference> out = new ArrayList<>();
		var sql = "SELECT idType, id FROM record WHERE " + where
				+ (order == null ? "" : " " + order);
		try (Statement s = this.connection.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				out.add(
					Builder.recordReference(
						IdType.valueOf(rs.getString(1)), rs.getString(2)
					)
				);
			}
		} catch (SQLException e) {
			throw new RepositoryException("could not read the repository", e);
		}
		return out;
	}

	private Stream<Record> hydrate(List<ImmutableRecordReference> keys) {
		return keys.stream()
			.map(this::getRecord)
			.filter(Optional::isPresent)
			.map(Optional::get);
	}

	@Override
	public Stream<CitationLink> citations() {
		List<CitationLink> out = new ArrayList<>();
		try (Statement s = this.connection.createStatement();
				ResultSet rs = s.executeQuery(
					"SELECT srcIdType, srcId, tgtIdType, tgtId, idx FROM citation"
				)) {
			while (rs.next()) {
				var source = Builder.recordReference(
					IdType.valueOf(rs.getString(1)), rs.getString(2)
				);
				var target = Builder.recordReference(
					IdType.valueOf(rs.getString(3)), rs.getString(4)
				);
				int index = rs.getInt(5);
				out.add(
					Builder.citationLink(
						source, target, rs.wasNull() ? null : index
					)
				);
			}
		} catch (SQLException e) {
			throw new RepositoryException("could not read the citations", e);
		}
		return out.stream();
	}

	@Override
	public long recordCount() { return count("record"); }

	@Override
	public long citationCount() { return count("citation"); }

	private long count(String table) {
		try (Statement s = this.connection.createStatement();
				ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
			rs.next();
			return rs.getLong(1);
		} catch (SQLException e) {
			throw new RepositoryException("could not count " + table, e);
		}
	}

	// ---- plumbing -----------------------------------------------------------

	private static ImmutableRecordReference keyOf(RecordReference ref) {
		return ref.asId();
	}

	private static Optional<ImmutableRecordReference> endpoint(
			uk.co.terminological.bibliography.record.CitationReference reference
	) {
		return reference.getIdentifier()
			.filter(
				r -> r.getIdentifier()
					.isPresent()
			)
			.map(RecordReference::asId);
	}

	private void rollback() {
		try {
			this.connection.rollback();
		} catch (SQLException ignored) {
			// the commit already failed; the rollback failing adds nothing
		}
	}

	private void autoCommit() {
		try {
			this.connection.setAutoCommit(true);
		} catch (SQLException ignored) {
			// likewise
		}
	}

	// ---- crawl jobs ---------------------------------------------------------

	@Override
	public void putJob(CrawlJob job) {
		try {
			this.connection.setAutoCommit(false);
			try (PreparedStatement p = this.connection.prepareStatement(
				"INSERT INTO job (id, direction, depth, maxRecords, status, errors,"
						+ " stoppedBy, startedAt, finishedAt, callsPerApi)"
						+ " VALUES (?,?,?,?,?,?,?,?,?,?)"
						+ " ON CONFLICT (id) DO UPDATE SET status=excluded.status,"
						+ " errors=excluded.errors, stoppedBy=excluded.stoppedBy,"
						+ " startedAt=excluded.startedAt, finishedAt=excluded.finishedAt,"
						+ " callsPerApi=excluded.callsPerApi"
			)) {
				p.setString(1, job.getId());
				p.setString(2, job.getDirection()
					.name());
				p.setInt(3, job.getDepth());
				p.setInt(4, job.getMaxRecords());
				p.setString(5, job.getStatus()
					.name());
				p.setInt(6, job.getErrors());
				p.setString(
						7, job.getStoppedBy()
							.orElse(null)
				);
				p.setString(
						8, job.getStartedAt()
							.map(Object::toString)
							.orElse(null)
				);
				p.setString(
						9, job.getFinishedAt()
							.map(Object::toString)
							.orElse(null)
				);
				p.setString(10, encodeCalls(job.getCallsPerApi()));
				p.executeUpdate();
			}
			// the frontier is rewritten rather than diffed: it is a few thousand rows
			// at the scales this runs at, and a diff is a chance to lose a node
			try (PreparedStatement d = this.connection
				.prepareStatement("DELETE FROM job_node WHERE jobId = ?")) {
				d.setString(1, job.getId());
				d.executeUpdate();
			}
			try (PreparedStatement p = this.connection.prepareStatement(
				"INSERT OR REPLACE INTO job_node (jobId, idType, id, depth, seed,"
						+ " visited, queued, position) VALUES (?,?,?,?,?,?,?,?)"
			)) {
				var queued = new ArrayList<>(job.getFrontier());
				for (var entry : job.getDepthOf()
					.entrySet()) {
					var ref = entry.getKey();
					int position = queued.indexOf(ref);
					p.setString(1, job.getId());
					p.setString(2, ref.getIdentifierType()
						.name());
					p.setString(
							3, ref.getIdentifier()
								.orElse("")
					);
					p.setInt(4, entry.getValue());
					p.setInt(
							5, job.getSeeds()
								.contains(ref) ? 1 : 0
					);
					p.setInt(
							6, job.getVisited()
								.contains(ref) ? 1 : 0
					);
					p.setInt(7, position >= 0 ? 1 : 0);
					if (position >= 0) p.setInt(8, position);
					else p.setNull(8, java.sql.Types.INTEGER);
					p.addBatch();
				}
				p.executeBatch();
			}
			this.connection.commit();
		} catch (SQLException e) {
			rollback();
			throw new RepositoryException(
					"could not checkpoint crawl job " + job.getId(), e
			);
		} finally {
			autoCommit();
		}
	}

	@Override
	public Optional<CrawlJob> job(String id) {
		try (PreparedStatement p = this.connection.prepareStatement(
			"SELECT direction, depth, maxRecords, status, errors, stoppedBy,"
					+ " startedAt, finishedAt, callsPerApi FROM job WHERE id = ?"
		)) {
			p.setString(1, id);
			try (ResultSet rs = p.executeQuery()) {
				if (!rs.next()) return Optional.empty();
				return Optional.of(readJob(id, rs));
			}
		} catch (SQLException e) {
			throw new RepositoryException("could not read crawl job " + id, e);
		}
	}

	@Override
	public Stream<CrawlJob> jobs() {
		List<String> ids = new ArrayList<>();
		try (Statement s = this.connection.createStatement();
				ResultSet rs = s.executeQuery(
					"SELECT id FROM job ORDER BY coalesce(startedAt, '') DESC"
				)) {
			while (rs.next())
				ids.add(rs.getString(1));
		} catch (SQLException e) {
			throw new RepositoryException("could not list the crawl jobs", e);
		}
		return ids.stream()
			.map(this::job)
			.filter(Optional::isPresent)
			.map(Optional::get);
	}

	private CrawlJob readJob(String id, ResultSet rs) throws SQLException {
		var direction = Direction.valueOf(rs.getString("direction"));
		int depth = rs.getInt("depth");
		int maxRecords = rs.getInt("maxRecords");
		var status = JobStatus.valueOf(rs.getString("status"));
		int errors = rs.getInt("errors");
		var stoppedBy = rs.getString("stoppedBy");
		var startedAt = rs.getString("startedAt");
		var finishedAt = rs.getString("finishedAt");
		var calls = decodeCalls(rs.getString("callsPerApi"));

		Map<ImmutableRecordReference, Integer> depthOf = new LinkedHashMap<>();
		Set<ImmutableRecordReference> seeds = new LinkedHashSet<>();
		Set<ImmutableRecordReference> visited = new LinkedHashSet<>();
		List<ImmutableRecordReference> frontier = new ArrayList<>();
		try (PreparedStatement p = this.connection.prepareStatement(
			"SELECT idType, id, depth, seed, visited, queued FROM job_node"
					+ " WHERE jobId = ? ORDER BY coalesce(position, 1 << 30), rowid"
		)) {
			p.setString(1, id);
			try (ResultSet nodes = p.executeQuery()) {
				while (nodes.next()) {
					var ref = Builder.recordReference(
						IdType.valueOf(nodes.getString("idType")), nodes.getString("id")
					);
					depthOf.put(ref, nodes.getInt("depth"));
					if (nodes.getInt("seed") == 1) seeds.add(ref);
					if (nodes.getInt("visited") == 1) visited.add(ref);
					if (nodes.getInt("queued") == 1) frontier.add(ref);
				}
			}
		}
		var job = new CrawlJob(id, seeds, direction, depth, maxRecords);
		job.restore(
			depthOf, frontier, visited, status, errors, stoppedBy,
			startedAt == null ? null : Instant.parse(startedAt),
			finishedAt == null ? null : Instant.parse(finishedAt), calls
		);
		return job;
	}

	/** api=count pairs; a map small enough that a format of its own would be ceremony */
	private static String encodeCalls(Map<String, Integer> calls) {
		return calls.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + e.getValue())
			.collect(java.util.stream.Collectors.joining(";"));
	}

	private static Map<String, Integer> decodeCalls(String encoded) {
		Map<String, Integer> out = new LinkedHashMap<>();
		if (encoded == null || encoded.isBlank()) return out;
		for (String pair : encoded.split(";")) {
			int eq = pair.lastIndexOf('=');
			if (eq < 0) continue;
			try {
				out.put(
					pair.substring(0, eq), Integer.parseInt(pair.substring(eq + 1))
				);
			} catch (NumberFormatException e) {
				// a call count that cannot be read is not worth failing a resume for
			}
		}
		return out;
	}

	@Override
	public void close() {
		try {
			this.connection.close();
		} catch (SQLException e) {
			throw new RepositoryException("could not close the repository", e);
		} finally {
			if (this.lock != null) this.lock.release();
		}
	}

}
