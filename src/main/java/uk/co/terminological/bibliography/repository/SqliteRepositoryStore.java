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
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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

	private SqliteRepositoryStore(Connection connection, Path file) {
		this.connection = connection;
		this.file = file;
	}

	/** Open, creating the repository when the file does not exist yet. */
	public static SqliteRepositoryStore open(Path file) {
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
			var store = new SqliteRepositoryStore(connection, file);
			store.createOrCheckSchema();
			return store;
		} catch (SQLException | java.io.IOException e) {
			throw new RepositoryException(
					"could not open the repository at " + file, e
			);
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
	public void markSelected(RecordReference ref, boolean selected) {
		update(
			"UPDATE record SET selected = ? WHERE idType = ? AND id = ?", ref,
			p -> p.setInt(1, selected ? 1 : 0)
		);
	}

	private interface Binder {
		void bind(PreparedStatement p) throws SQLException;
	}

	private void update(String sql, RecordReference ref, Binder first) {
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

	@Override
	public Collection<Record> contributions(RecordReference ref) {
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

	@Override
	public void close() {
		try {
			this.connection.close();
		} catch (SQLException e) {
			throw new RepositoryException("could not close the repository", e);
		}
	}

}
