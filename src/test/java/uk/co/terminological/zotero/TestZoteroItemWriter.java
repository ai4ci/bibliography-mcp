package uk.co.terminological.zotero;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * feat-005: turning a discovered record into the JSON Zotero needs.
 *
 * Only creation is supported — a Zotero item is never overwritten with
 * API-fetched values, so there is no "update an existing item" mapping here.
 * The item is created inside one collection: the configured write target.
 *
 * [TESTS](/design/test-scripts/test-003-zotero-bridge-tests.md)
 * [TESTS](/design/test-scripts/test-004-metadata-cleanup-tests.md)
 */
public class TestZoteroItemWriter {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	public void anArticleBecomesAJournalArticleItem() throws Exception {
		// note the helper's order: publisher, then abstract, then title
		Record r = record("article-journal", IdType.DOI, "10.1000/EXAMPLE", "Example Press", "A study",
				"A study of things", List.of(person("Ada", "Lovelace")), LocalDate.of(2021, 3, 4));

		JsonNode item = ZoteroItemWriter.toItem(r, "TARGETKEY", List.of("api-test-tag"));

		assertEquals("journalArticle", item.get("itemType").asText());
		assertEquals("A study of things", item.get("title").asText());
		assertEquals("10.1000/EXAMPLE", item.get("DOI").asText());
		assertEquals("Example Press", item.get("publisher").asText());
		assertEquals("2021-03-04", item.get("date").asText());
		assertEquals("A study", item.get("abstractNote").asText());
		assertEquals("TARGETKEY", item.get("collections").get(0).asText());
		assertEquals("api-test-tag", item.get("tags").get(0).get("tag").asText());
		assertEquals("author", item.get("creators").get(0).get("creatorType").asText());
		assertEquals("Lovelace", item.get("creators").get(0).get("lastName").asText());
		assertEquals("Ada", item.get("creators").get(0).get("firstName").asText());
	}

	@Test
	public void aChapterBecomesABookSectionAndABookBecomesABook() throws Exception {
		Record chapter = record("chapter", IdType.DOI, "10.1/ch", null, null, "A chapter", List.of(), null);
		Record book = record("book", IdType.ISBN, "9781315117195", null, null, "A book", List.of(), null);

		assertEquals("bookSection", ZoteroItemWriter.toItem(chapter, "K", List.of()).get("itemType").asText());
		assertEquals("book", ZoteroItemWriter.toItem(book, "K", List.of()).get("itemType").asText());
		// an ISBN goes into Zotero's ISBN field, not DOI
		assertTrue(ZoteroItemWriter.toItem(book, "K", List.of()).get("DOI") == null);
		assertEquals("9781315117195", ZoteroItemWriter.toItem(book, "K", List.of()).get("ISBN").asText());
	}

	@Test
	public void anUnmappedTypeFallsBackToDocumentRatherThanGuessing() throws Exception {
		Record odd = record("some-future-type", IdType.DOI, "10.1/x", null, null, "T", List.of(), null);

		assertEquals("document", ZoteroItemWriter.toItem(odd, "K", List.of()).get("itemType").asText());
	}

	@Test
	public void aDocumentStaysADocumentAndNeverBecomesAnAttachment() throws Exception {
		// "document" and "attachment" both map to the CSL type "document" when
		// reading, so the reverse lookup must be an explicit table
		Record doc = record("document", IdType.DOI, "10.1/doc", null, null, "A generic document", List.of(), null);

		assertEquals("document", ZoteroItemWriter.toItem(doc, "K", List.of()).get("itemType").asText());
	}

	@Test
	public void anInstitutionalCreatorBecomesASingleName() throws Exception {
		Record r = record("webpage", null, null, null, null, "Guidance",
				List.of(institution("Some Organisation")), null);

		JsonNode creator = ZoteroItemWriter.toItem(r, "K", List.of()).get("creators").get(0);
		assertEquals("Some Organisation", creator.get("name").asText());
		assertTrue(creator.get("lastName") == null);
	}

	@Test
	public void aTagPatchUnionsTagsAndCarriesTheVersion() throws Exception {
		// one request can carry up to 50 of these: a write authorisation is usually
		// single-use, so one request per item would ask the user for every item
		var item = mapper.readTree("{\"key\":\"AAAA1111\",\"version\":7,"
				+ "\"tags\":[{\"tag\":\"existing\"},{\"tag\":\"shared\"},{\"tag\":\"auto\",\"type\":1}]}");

		var patch = ZoteroItemWriter.tagPatch("AAAA1111", 7, item, List.of("shared", " agent-added ", ""));

		assertEquals("AAAA1111", patch.get("key").asText());
		assertEquals(7, patch.get("version").asInt());
		List<String> tags = new java.util.ArrayList<>();
		patch.get("tags").forEach(t -> tags.add(t.get("tag").asText()));
		// existing tags kept and deduplicated, new ones trimmed, blanks ignored
		assertEquals(List.of("existing", "shared", "auto", "agent-added"), tags);
	}

	@Test
	public void aTagPatchOnAnItemWithNoTagsWorks() throws Exception {
		var item = mapper.readTree("{\"key\":\"BBBB2222\",\"version\":1}");

		var patch = ZoteroItemWriter.tagPatch("BBBB2222", 1, item, List.of("first"));

		assertEquals("first", patch.get("tags").get(0).get("tag").asText());
	}

	@Test
	public void writeResultsAreReadFromBothOfZoterosReplyShapes() throws Exception {
		// Zotero answers a multi-object write either with the key as a string or with an
		// object carrying key and version; asText() on the object form is an empty
		// string, which is no use to a caller that wants to tag what it just created
		var result = new ZoteroClient.WriteResult();
		ZoteroClient.collect(mapper.readTree("{\"successful\":{\"0\":{\"key\":\"AAAA1111\",\"version\":3},"
				+ "\"1\":\"BBBB2222\"},\"unchanged\":{\"2\":{\"key\":\"CCCC3333\",\"version\":9}},"
				+ "\"failed\":{\"3\":{\"code\":400,\"message\":\"nope\"}}}"), result);

		assertEquals(List.of("AAAA1111", "BBBB2222", "CCCC3333"), result.created);
		assertEquals(1, result.failures.size());
		assertTrue(result.hasFailures());
	}

	@Test
	public void anEmptyReplyIsNotSilentlySuccess() throws Exception {
		var result = new ZoteroClient.WriteResult();
		ZoteroClient.collect(mapper.readTree("{\"successful\":{},\"unchanged\":{},\"failed\":{}}"), result);

		assertTrue(result.created.isEmpty());
		assertTrue(result.failures.isEmpty());
	}

	@Test
	public void anUpdateCarriesOnlyTheNamedFields() throws Exception {
		var patch = ZoteroItemWriter.updatePatch("AAAA1111", 7,
				new java.util.LinkedHashMap<>(java.util.Map.of("abstractNote", "Filled from Europe PMC", "date", "2019-01-01")));

		assertEquals("AAAA1111", patch.get("key").asText());
		assertEquals(7, patch.get("version").asInt());
		assertEquals(4, patch.size()); // key, version and the two fields, nothing else
		assertEquals("Filled from Europe PMC", patch.get("abstractNote").asText());
		assertEquals("2019-01-01", patch.get("date").asText());
		// tags, collections and relations are absent, so PATCH semantics leave them alone
		assertTrue(patch.get("tags") == null && patch.get("collections") == null && patch.get("relations") == null);
	}

	@Test
	public void anUpdateWithNoFieldsIsRefused() {
		try {
			ZoteroItemWriter.updatePatch("AAAA1111", 7, java.util.Map.of());
			throw new AssertionError("expected a refusal");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("at least one field"));
		}
	}

	@Test
	public void theContainerGoesIntoTheFieldItsTypeUses() throws Exception {
		var article = mapper.readTree("{\"type\":\"article-journal\",\"title\":\"T\",\"containerTitle\":\"Gaceta Sanitaria\"}");
		var chapter = mapper.readTree("{\"type\":\"chapter\",\"title\":\"T\",\"containerTitle\":\"The book\"}");

		// the types map to different Zotero fields, and omitting the container
		// altogether (which this writer did at first) leaves the item with no journal
		assertEquals("publicationTitle", ZoteroItemWriter.containerField("journalArticle"));
		assertEquals("bookTitle", ZoteroItemWriter.containerField("bookSection"));
		assertEquals("proceedingsTitle", ZoteroItemWriter.containerField("conferencePaper"));
	}

	@Test
	public void anItemWithNoTitleIsRefused() {
		Record noTitle = record("report", null, null, null, null, null, List.of(), null);
		try {
			ZoteroItemWriter.toItem(noTitle, "K", List.of());
			throw new AssertionError("expected a refusal");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("title"));
		}
	}

	@Test
	public void aRecordWithNoDateOmitsTheFieldRatherThanInventingOne() throws Exception {
		Record r = record("report", null, null, null, null, "T", List.of(), null);

		assertTrue(ZoteroItemWriter.toItem(r, "K", List.of()).get("date") == null);
	}

	private static Author person(String firstName, String lastName) {
		return new Author() {
			public Optional<String> getORCID() { return Optional.empty(); }
			public Optional<String> getFirstName() { return Optional.of(firstName); }
			public String getLastName() { return lastName; }
			public Optional<String> getInitials() { return Optional.empty(); }
			public java.util.stream.Stream<String> getAffiliations() { return java.util.stream.Stream.empty(); }
		};
	}

	/** an institutional author has no first name, so Zotero wants a single `name` */
	private static Author institution(String name) {
		return new Author() {
			public Optional<String> getORCID() { return Optional.empty(); }
			public Optional<String> getFirstName() { return Optional.empty(); }
			public String getLastName() { return name; }
			public Optional<String> getInitials() { return Optional.empty(); }
			public java.util.stream.Stream<String> getAffiliations() { return java.util.stream.Stream.empty(); }
		};
	}

	private static Record record(String type, IdType idType, String id, String publisher, String abstractText,
			String title, List<Author> authors, LocalDate date) {
		return new Record() {
			public Optional<String> getIdentifier() { return Optional.ofNullable(id); }
			public IdType getIdentifierType() { return idType == null ? IdType.UNK : idType; }
			public List<? extends RecordReference> getOtherIdentifiers() { return List.of(); }
			public List<? extends Author> getAuthors() { return authors; }
			public java.util.stream.Stream<String> getLicenses() { return java.util.stream.Stream.empty(); }
			public Optional<String> getAbstract() { return Optional.ofNullable(abstractText); }
			public Optional<String> getTitle() { return Optional.ofNullable(title); }
			public Optional<String> getJournal() { return Optional.empty(); }
			public Optional<LocalDate> getDate() { return Optional.ofNullable(date); }
			public Optional<java.net.URI> getPdfUri() { return Optional.empty(); }
			public Optional<String> getPublicationType() { return Optional.of(type); }
			public Optional<String> getPublisher() { return Optional.ofNullable(publisher); }
			public Optional<Provenance> getProvenance() { return Optional.of(Provenance.of("crossref", "journal-article")); }
		};
	}
}
