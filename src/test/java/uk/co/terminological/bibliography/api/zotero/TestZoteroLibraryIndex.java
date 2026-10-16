package uk.co.terminological.bibliography.api.zotero;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * feat-005: deciding whether a record the APIs found is already in the library.
 *
 * Only 52.6% of the developer's library carries a DOI and 30.8% has no DOI, ISBN or URL at all, so matching cannot be
 * DOI-first-and-done: title+year has to work on its own.
 *
 * [TESTS](/design/test-scripts/test-003-zotero-bridge-tests.md)
 */
public class TestZoteroLibraryIndex {

	private final ObjectMapper mapper = new ObjectMapper();

	private ZoteroItem item(String json) throws Exception {
		var data = this.mapper.readTree(json)
			.get("data");
		return new ZoteroItem(data, "TESTSERVERID");
	}

	private ZoteroLibraryIndex index(ZoteroItem... items) {
		return ZoteroLibraryIndex.of(List.of(items), "TESTSERVERID", 42L);
	}

	@Test
	public void matchesOnDoiRegardlessOfPrefixOrCase() throws Exception {
		var idx = this.index(
			this.item(
				"{\"data\":{\"key\":\"K1\",\"itemType\":\"journalArticle\","
						+ "\"title\":\"T\",\"DOI\":\"10.1000/Example\"}}"
			)
		);

		assertEquals(
			"K1",
			idx.findKey(stub(IdType.DOI, "10.1000/example", "T", 2021))
				.orElse(null)
		);
		assertEquals(
			"K1",
			idx.findKey(
				stub(IdType.DOI, "https://doi.org/10.1000/EXAMPLE", "T", 2021)
			)
				.orElse(null)
		);
	}

	@Test
	public void matchesOnIsbnIgnoringHyphens() throws Exception {
		var idx = this.index(
			this.item(
				"{\"data\":{\"key\":\"K2\",\"itemType\":\"book\","
						+ "\"title\":\"A book\",\"ISBN\":\"978-1-315-11719-5\"}}"
			)
		);

		assertEquals(
			"K2",
			idx.findKey(stub(IdType.ISBN, "9781315117195", "A book", 2018))
				.orElse(null)
		);
	}

	@Test
	public void matchesOnTitleAndYearWhenThereIsNoIdentifierAtAll()
			throws Exception {
		// the 30.8% case: nothing but a title and a year
		var idx = this.index(
			this.item(
				"{\"data\":{\"key\":\"K3\",\"itemType\":\"report\","
						+ "\"title\":\"A commissioned review of things\",\"date\":\"2022\"}}"
			)
		);

		var found = idx
			.findKey(stub(null, null, "A commissioned review of things!", 2022));
		assertEquals("K3", found.orElse(null));
	}

	@Test
	public void punctuationAndCaseDoNotAffectATitleMatch() throws Exception {
		var idx = this.index(
			this.item(
				"{\"data\":{\"key\":\"K4\",\"itemType\":\"journalArticle\","
						+ "\"title\":\"Health data: a review\",\"date\":\"2020-05-01\"}}"
			)
		);

		assertEquals(
			"K4",
			idx.findKey(stub(null, null, "health data - A REVIEW", 2020))
				.orElse(null)
		);
	}

	@Test
	public void aDifferentYearOrTitleDoesNotMatch() throws Exception {
		var idx = this.index(
			this.item(
				"{\"data\":{\"key\":\"K5\",\"itemType\":\"journalArticle\","
						+ "\"title\":\"Health data: a review\",\"date\":\"2020-05-01\"}}"
			)
		);

		assertTrue(
			idx.findKey(stub(null, null, "Health data: a review", 2019))
				.isEmpty()
		);
		assertTrue(
			idx.findKey(stub(null, null, "Health data: another review", 2020))
				.isEmpty()
		);
	}

	@Test
	public void aCandidateWithNoYearStillMatchesOnDoi() throws Exception {
		var idx = this.index(
			this.item(
				"{\"data\":{\"key\":\"K6\",\"itemType\":\"journalArticle\","
						+ "\"title\":\"T\",\"DOI\":\"10.1000/x\"}}"
			)
		);

		assertEquals(
			"K6",
			idx.findKey(stub(IdType.DOI, "10.1000/x", "T", null))
				.orElse(null)
		);
		// ...but a title-only candidate with no year cannot match, and says so
		assertTrue(
			idx.findKey(stub(null, null, "T", null))
				.isEmpty()
		);
	}

	@Test
	public void reportsItsSizeAndVersion() throws Exception {
		var idx = this.index(
			this.item(
				"{\"data\":{\"key\":\"K7\",\"itemType\":\"journalArticle\",\"title\":\"T\",\"DOI\":\"10.1000/a\"}}"
			),
			this.item(
				"{\"data\":{\"key\":\"K8\",\"itemType\":\"book\",\"title\":\"B\",\"ISBN\":\"9781315117195\"}}"
			)
		);

		assertEquals(2, idx.size());
		assertEquals(42L, idx.getLibraryVersion());
		assertEquals("TESTSERVERID", idx.getServerId());
	}

	// a stand-in for a record discovered from another API

	private static Record stub(
			IdType type, String id, String title, Integer year
	) {
		return new Record() {
			@Override
			public Optional<String> getIdentifier() {
				return Optional.ofNullable(id);
			}
			@Override
			public IdType getIdentifierType() {
				return type == null ? IdType.UNK : type;
			}
			@Override
			public List<RecordReference> getOtherIdentifiers() {
				return List.of();
			}
			@Override
			public List<Author> getAuthors() { return List.of(); }
			@Override
			public java.util.stream.Stream<String> getLicenses() {
				return java.util.stream.Stream.empty();
			}
			@Override
			public Optional<String> getAbstract() { return Optional.empty(); }
			@Override
			public Optional<String> getTitle() {
				return Optional.ofNullable(title);
			}
			@Override
			public Optional<String> getJournal() { return Optional.empty(); }
			@Override
			public Optional<LocalDate> getDate() {
				return Optional.ofNullable(year)
					.map(y -> LocalDate.of(y, 1, 1));
			}
			public java.net.URI getNothing() { return null; }
			@Override
			public Optional<java.net.URI> getPdfUri() { return Optional.empty(); }
			@Override
			public Provenance getProvenance() {
				return Provenance.of("crossref", "journal-article");
			}
		};
	}
}
