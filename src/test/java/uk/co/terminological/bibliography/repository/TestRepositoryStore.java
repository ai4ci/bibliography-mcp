package uk.co.terminological.bibliography.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * plan-010: the repository of record, durable and queryable.
 *
 * What these assert is the contract the crawl depends on — a record survives the process, two sources describing one
 * work become one record rather than two, a selection is a reversible mark rather than a delete, and the top of the
 * repository can be read without loading it. Java serialization gave none of that (debt-001).
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestRepositoryStore {

	private Path dir;
	private RepositoryStore store;

	@Before
	public void open() throws Exception {
		this.dir = Files.createTempDirectory("biblio-store-test");
		// exclusive, as a server opens it: the lock tests need a holder
		this.store = SqliteRepositoryStore
			.open(this.dir.resolve("repo.db"), true);
	}

	@After
	public void close() throws Exception {
		if (this.store != null) this.store.close();
	}

	private static Record record(
			IdType type, String id, String title, String source
	) {
		return new StoredRecordTest.Stub(type, id, title, source);
	}

	// ---- identity and round trip ----

	@Test
	public void aRecordSurvivesTheProcessThatWroteIt() throws Exception {
		var doi = Builder.recordReference(IdType.DOI, "10.1136/bmjqs-2018-008370");
		this.store.putRecord(
			record(IdType.DOI, "10.1136/bmjqs-2018-008370", "A title", "crossref")
		);
		this.store.close();

		// a different store object over the same file: what a restart sees
		try (var reopened = SqliteRepositoryStore
			.open(this.dir.resolve("repo.db"))) {
			var found = reopened.getRecord(doi);
			assertTrue("the record did not survive", found.isPresent());
			assertEquals(
				Optional.of("A title"),
				found.get()
					.getTitle()
			);
			assertEquals(
				IdType.DOI,
				found.get()
					.getIdentifierType()
			);
		}
	}

	@Test
	public void theSchemaVersionIsInTheFile() {
		assertEquals(
			SqliteRepositoryStore.SCHEMA_VERSION,
			this.store.meta()
				.schemaVersion()
		);
	}

	@Test
	public void aFileFromALaterSchemaIsRefusedByName() throws Exception {
		this.store.close();
		SqliteRepositoryStore.setSchemaVersionForTest(
			this.dir.resolve("repo.db"), SqliteRepositoryStore.SCHEMA_VERSION + 7
		);

		try {
			SqliteRepositoryStore.open(this.dir.resolve("repo.db"))
				.close();
			fail("a repository from a later version should not be opened");
		} catch (RepositoryException e) {
			// naming both versions is the whole point: an InvalidClassException
			// naming a serialVersionUID is what debt-001 was about
			assertTrue(
				e.getMessage(),
				e.getMessage()
					.contains(
						Integer.toString(SqliteRepositoryStore.SCHEMA_VERSION + 7)
					)
			);
			assertTrue(
				e.getMessage(),
				e.getMessage()
					.contains(
						Integer.toString(SqliteRepositoryStore.SCHEMA_VERSION)
					)
			);
		}
	}

	// ---- merging ----

	@Test
	public void twoSourcesDescribingOneWorkAreOneRecord() {
		this.store.putRecord(
			record(IdType.DOI, "10.1/x", "Crossref's title", "crossref")
		);
		this.store.putRecord(
			record(IdType.DOI, "10.1/x", "Europe PMC's title", "europepmc")
		);

		assertEquals(
			1,
			this.store.records()
				.count()
		);
		// MergePolicy ranks Crossref first for a title, and the store applies it on
		// write so that a top-N query never has to merge anything
		assertEquals(
			Optional.of("Crossref's title"),
			this.store.getRecord(Builder.recordReference(IdType.DOI, "10.1/x"))
				.flatMap(Record::getTitle)
		);
	}

	@Test
	public void aRecordKeepsWhatEachSourceSaid() {
		this.store.putRecord(
			record(IdType.DOI, "10.1/x", "Crossref's title", "crossref")
		);
		this.store.putRecord(
			record(IdType.DOI, "10.1/x", "Europe PMC's title", "europepmc")
		);

		// the contributions are kept, not just the winner: the clean-up report needs
		// to say which source asserted what, and a later merge needs them to re-merge
		// properly rather than merging into an already-merged record
		var sources = this.store
			.contributions(Builder.recordReference(IdType.DOI, "10.1/x"))
			.stream()
			.map(
				r -> r.getProvenance()
					.getSource()
			)
			.sorted()
			.collect(Collectors.toList());
		assertEquals(List.of("crossref", "europepmc"), sources);
	}

	// ---- citations ----

	@Test
	public void citationsRoundTripInTheDirectionTheyWereStored() {
		var citing = Builder.recordReference(IdType.DOI, "10.1/citing");
		var cited = Builder.recordReference(IdType.DOI, "10.1/cited");
		this.store.putCitation(Builder.citationLink(citing, cited, 1));

		var links = this.store.citations()
			.collect(Collectors.toList());
		assertEquals(1, links.size());
		// source cites target — the convention issue-007 settled
		assertEquals(
			Optional.of("10.1/citing"),
			links.get(0)
				.getSource()
				.getIdentifier()
				.flatMap(RecordReference::getIdentifier)
		);
		assertEquals(
			Optional.of("10.1/cited"),
			links.get(0)
				.getTarget()
				.getIdentifier()
				.flatMap(RecordReference::getIdentifier)
		);
	}

	@Test
	public void theSameCitationTwiceIsOneEdge() {
		var citing = Builder.recordReference(IdType.DOI, "10.1/citing");
		var cited = Builder.recordReference(IdType.DOI, "10.1/cited");
		this.store.putCitation(Builder.citationLink(citing, cited, 1));
		this.store.putCitation(Builder.citationLink(citing, cited, 1));

		assertEquals(
			1,
			this.store.citations()
				.count()
		);
	}

	// ---- selection ----

	@Test
	public void selectionIsAReversibleMarkAndNotADelete() {
		var ref = Builder.recordReference(IdType.DOI, "10.1/x");
		this.store.putRecord(record(IdType.DOI, "10.1/x", "A title", "crossref"));
		this.store.putRecord(record(IdType.DOI, "10.1/y", "Another", "crossref"));

		this.store.markSelected(ref, true);
		assertEquals(
			1,
			this.store.selected()
				.count()
		);
		assertEquals(
			"nothing is removed by selecting",
			2,
			this.store.records()
				.count()
		);

		this.store.markSelected(ref, false);
		assertEquals(
			0,
			this.store.selected()
				.count()
		);
		assertEquals(
			2,
			this.store.records()
				.count()
		);
	}

	@Test
	public void aWorkIsFoundByAnyIdentifierItIsKnownBy() {
		// the crawl stores a record under the identifier it fetched by, while an edge
		// names the same work by whichever identifier the citing source happened to
		// use. The store already records the equivalence; it has to use it, or the
		// most-cited works in a crawl come back as anonymous placeholders.
		var record = uk.co.terminological.bibliography.record.ImmutableRecord
			.builder()
			.identifierType(IdType.DOI)
			.identifier("10.1/known")
			.provenance(
				uk.co.terminological.bibliography.record.Provenance
					.of("crossref", "journal-article")
			)
			.licenses(java.util.stream.Stream.empty())
			.title(Optional.of("Known by two names"))
			.addOtherIdentifiers(Builder.recordReference(IdType.PMID, "99999"))
			.build();
		this.store.putRecord(record);

		var byPmid = this.store
			.getRecord(Builder.recordReference(IdType.PMID, "99999"));

		assertTrue(
			"a work stored by DOI was not found by the PMID it is also known by",
			byPmid.isPresent()
		);
		assertEquals(
			Optional.of("Known by two names"),
			byPmid.flatMap(Record::getTitle)
		);
	}

	@Test
	public void theMostCitedWorkIsNamedRatherThanAnonymous() {
		var cited = uk.co.terminological.bibliography.record.ImmutableRecord
			.builder()
			.identifierType(IdType.DOI)
			.identifier("10.1/cited")
			.provenance(
				uk.co.terminological.bibliography.record.Provenance
					.of("crossref", "journal-article")
			)
			.licenses(java.util.stream.Stream.empty())
			.title(Optional.of("The work everyone cites"))
			.addOtherIdentifiers(
				Builder.recordReference(IdType.OPENALEX, "W123")
			)
			.build();
		this.store.putRecord(cited);
		// the edge names it the way the citing source did
		this.store.putCitation(
			Builder.citationLink(
				Builder.recordReference(IdType.DOI, "10.1/citing"),
				Builder.recordReference(IdType.OPENALEX, "W123"), 1
			)
		);

		var top = this.store.topByCitedBy(1);

		assertEquals(1, top.size());
		assertEquals(
			Optional.of("The work everyone cites"),
			top.get(0)
				.getKey()
				.getTitle()
		);
	}

	// ---- the query the store exists for ----

	@Test
	public void theTopOfTheRepositoryIsReadableWithoutLoadingIt() {
		for (int i = 0; i < 5000; i++) {
			this.store.putRecord(
				record(IdType.DOI, "10.1/rec" + i, "Record " + i, "crossref")
			);
			this.store.setScore(
				Builder.recordReference(IdType.DOI, "10.1/rec" + i), i / 1000.0
			);
		}

		var top = this.store.topByScore(10)
			.collect(Collectors.toList());

		assertEquals(10, top.size());
		assertEquals(
			"the highest score should come first",
			Optional.of("Record 4999"),
			top.get(0)
				.getTitle()
		);
	}

	@Test
	public void aReaderSeesTheRepositoryWhileAnotherIsWriting() throws Exception {
		this.store.putRecord(record(IdType.DOI, "10.1/x", "A title", "crossref"));

		// the case the crawl creates: a client asking what is there while the job
		// that is filling it still holds the store open
		try (var reader = SqliteRepositoryStore
			.open(this.dir.resolve("repo.db"))) {
			assertEquals(
				1,
				reader.records()
					.count()
			);
			this.store.putRecord(
				record(IdType.DOI, "10.1/y", "Another title", "crossref")
			);
			assertEquals(
				2,
				reader.records()
					.count()
			);
		}
	}

	// ---- one writer ----

	@Test
	public void aSecondServerOnTheSameRepositoryIsRefused() throws Exception {
		// two crawl engines writing to one repository would race on the same records
		// and spend two API budgets at once. SQLite's WAL makes concurrent readers
		// safe and says nothing about that.
		try {
			SqliteRepositoryStore.open(this.dir.resolve("repo.db"), true)
				.close();
			fail("a second writer should not have been allowed in");
		} catch (RepositoryException e) {
			assertTrue(
				e.getMessage(), e.getMessage()
					.contains("already")
			);
			// the message has to say what to do about it, because the usual cause is
			// a server the developer forgot is running
			assertTrue(
				e.getMessage(), e.getMessage()
					.toLowerCase()
					.contains("pid")
			);
		}
	}

	@Test
	public void aReaderIsStillWelcome() throws Exception {
		// the lock is on writing, not on looking: a second process reading while a
		// crawl runs is the case the store was chosen for
		try (var reader = SqliteRepositoryStore
			.open(this.dir.resolve("repo.db"), false)) {
			assertEquals(0, reader.recordCount());
		}
	}

	@Test
	public void theLockGoesWhenTheServerDoes() throws Exception {
		this.store.close();
		this.store = null;

		// closing releases it, so a restart is not blocked by its own last run
		try (var next = SqliteRepositoryStore
			.open(this.dir.resolve("repo.db"), true)) {
			assertEquals(0, next.recordCount());
		}
	}

	@Test
	public void aLockLeftBehindByADeadProcessDoesNotBlockForever()
			throws Exception {
		this.store.close();
		this.store = null;
		// what a kill -9 leaves: a lock file naming a pid that is no longer running
		Files.writeString(
			this.dir.resolve("repo.db.lock"), "999999" + System.lineSeparator()
		);

		try (var next = SqliteRepositoryStore
			.open(this.dir.resolve("repo.db"), true)) {
			assertEquals(0, next.recordCount());
		}
	}

	@Test
	public void anEmptyRepositoryIsEmptyRatherThanAbsent() {
		assertEquals(
			0,
			this.store.records()
				.count()
		);
		assertEquals(
			0,
			this.store.citations()
				.count()
		);
		assertFalse(
			this.store.getRecord(Builder.recordReference(IdType.DOI, "10.1/nope"))
				.isPresent()
		);
	}

	/** a minimal Record: identity, a title, and where the claim came from */
	static class StoredRecordTest {

		static class Stub implements Record {
			private final IdType idType;
			private final String id;
			private final String title;
			private final uk.co.terminological.bibliography.record.Provenance provenance;

			Stub(IdType idType, String id, String title, String source) {
				this.idType = idType;
				this.id = id;
				this.title = title;
				this.provenance = uk.co.terminological.bibliography.record.Provenance
					.of(source, "journal-article");
			}

			@Override
			public Optional<String> getIdentifier() { return Optional.of(this.id); }
			@Override
			public IdType getIdentifierType() { return this.idType; }
			@Override
			public List<RecordReference> getOtherIdentifiers() { return List.of(); }
			@Override
			public List<uk.co.terminological.bibliography.record.Author> getAuthors() {
				return List.of();
			}
			@Override
			public java.util.stream.Stream<String> getLicenses() {
				return java.util.stream.Stream.empty();
			}
			@Override
			public Optional<String> getAbstract() { return Optional.empty(); }
			@Override
			public Optional<String> getTitle() { return Optional.of(this.title); }
			@Override
			public Optional<String> getJournal() { return Optional.empty(); }
			@Override
			public Optional<LocalDate> getDate() { return Optional.empty(); }
			@Override
			public Optional<java.net.URI> getPdfUri() { return Optional.empty(); }
			@Override
			public uk.co.terminological.bibliography.record.Provenance getProvenance() {
				return this.provenance;
			}
		}
	}

}
