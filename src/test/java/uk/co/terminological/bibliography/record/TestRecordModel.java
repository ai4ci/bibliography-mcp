package uk.co.terminological.bibliography.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import uk.co.terminological.bibliography.api.crossref.CrossRefWork;
import uk.co.terminological.bibliography.api.europepmc.EuropePMCCoreResult;
import uk.co.terminological.bibliography.api.europepmc.EuropePMCLiteResult;
import uk.co.terminological.bibliography.mcp.McpTools;

/**
 * feat-006: a record can say what kind of work it is, who issued it, and where the claim came from.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestRecordModel {

	private final ObjectMapper mapper = new ObjectMapper()
		.registerModule(new Jdk8Module());

	// ---- CrossRef ----

	@Test
	public void crossrefWorkExposesCslTypeAndPublisher() throws Exception {
		// recorded CrossRef payload shape
		var work = new CrossRefWork(
				this.mapper.readTree(
					"{\"DOI\":\"10.1136/bmjqs-2018-008370\",\"type\":\"journal-article\","
							+ "\"publisher\":\"BMJ\",\"title\":[\"Artificial intelligence, bias and clinical safety\"],"
							+ "\"container-title\":[\"BMJ Quality & Safety\"]}"
				)
		);

		// CrossRef's own type vocabulary is not CSL's: journal-article -> article-journal
		assertEquals(
			"article-journal",
			work.getPublicationType()
				.orElse(null)
		);
		assertEquals(
			"BMJ",
			work.getPublisher()
				.orElse(null)
		);
		assertEquals(
			"BMJ Quality & Safety",
			work.getContainerTitle()
				.orElse(null)
		);

		assertEquals(
			"crossref",
			work.getProvenance()
				.getSource()
		);
		assertEquals(
			"journal-article",
			work.getProvenance()
				.getNativeType()
		);
	}

	@Test
	public void crossrefAbstractAndTitleComeBackAsPlainText() throws Exception {
		// Crossref returns the abstract as JATS XML: markup is not content, and it
		// must not reach a Zotero item or a report as markup either
		var work = new CrossRefWork(
				this.mapper.readTree(
					"{\"DOI\":\"10.1/x\",\"type\":\"journal-article\","
							+ "\"title\":[\"Health &amp; social care: a <i>review</i>\"],"
							+ "\"abstract\":\"<jats:title>ABSTRACT</jats:title><jats:p>We studied things &amp; found more.</jats:p>\"}"
				)
		);

		assertEquals(
			"Health & social care: a review",
			work.getTitle()
				.orElse(null)
		);
		assertEquals(
			"We studied things & found more.",
			work.getAbstract()
				.orElse(null)
		);
	}

	@Test
	public void crossrefBookChapterMapsToCslChapter() throws Exception {
		var work = new CrossRefWork(
				this.mapper.readTree(
					"{\"DOI\":\"10.1/y\",\"type\":\"book-chapter\",\"container-title\":[\"A Book\"]}"
				)
		);

		assertEquals(
			"chapter",
			work.getPublicationType()
				.orElse(null)
		);
		assertEquals(
			"A Book",
			work.getContainerTitle()
				.orElse(null)
		);
	}

	@Test
	public void unknownTypeDoesNotExplode() throws Exception {
		var work = new CrossRefWork(
				this.mapper
					.readTree("{\"DOI\":\"10.1/z\",\"type\":\"some-future-type\"}")
		);

		// the vocabulary is a string precisely so unknown values survive
		assertEquals(
			"some-future-type",
			work.getPublicationType()
				.orElse(null)
		);
	}

	// ---- Europe PMC, which needs both response shapes ----

	@Test
	public void europePmcLitePubTypeStringMapsToCsl() throws Exception {
		// lite: pubType is a semicolon-separated string
		var lite = new EuropePMCLiteResult(
				this.mapper.readTree(
					"{\"id\":\"30636200\",\"source\":\"MED\",\"doi\":\"10.1136/bmjqs-2018-008370\","
							+ "\"journalTitle\":\"BMJ Qual Saf\","
							+ "\"pubType\":\"research support, non-u.s. gov't; research-article; journal article\"}"
				)
		);

		assertEquals(
			"article-journal",
			lite.getPublicationType()
				.orElse(null)
		);
		assertTrue(
			lite.getProvenance()
				.getNativeType()
				.get()
				.contains("journal article")
		);
	}

	@Test
	public void europePmcCorePubTypeListMapsToCsl() throws Exception {
		// core: pubTypeList.pubType is an array
		var core = new EuropePMCCoreResult(
				this.mapper.readTree(
					"{\"id\":\"30636200\",\"source\":\"MED\",\"doi\":\"10.1136/bmjqs-2018-008370\","
							+ "\"pubTypeList\":{\"pubType\":[\"Research Support, Non-U.S. Gov't\",\"research-article\",\"Journal Article\"]}}"
				)
		);

		assertEquals(
			"article-journal",
			core.getPublicationType()
				.orElse(null)
		);
		assertEquals(
			"europepmc",
			core.getProvenance()
				.getSource()
		);
	}

	@Test
	public void reviewIsDistinguishedFromPlainArticle() throws Exception {
		var review = new EuropePMCLiteResult(
				this.mapper.readTree(
					"{\"id\":\"1\",\"pubType\":\"systematic review; review-article; journal article\"}"
				)
		);

		assertEquals(
			"review",
			review.getPublicationType()
				.orElse(null)
		);
	}

	// ---- container title ----

	@Test
	public void containerTitleFallsBackToJournal() {
		Record stub = new Stub(
				"10.1/a", "T", IdType.DOI, List.of(), null, null, null
		);

		assertEquals(
			"Some Journal",
			stub.getContainerTitle()
				.orElse(null)
		);
	}

	// ---- provenance survives merging ----

	@Test
	public void provenanceSurvivesMerging() {
		Record a = new Stub(
				"10.1/x", "Work", IdType.DOI, List.of(), "crossref",
				"journal-article", null
		);
		Record b = new Stub(
				"10.1/x", "Work", IdType.DOI, List.of(), "europepmc",
				"Journal Article", null
		);

		var merged = MergedRecord.from(a)
			.merge(b);

		var sources = merged.getProvenances()
			.stream()
			.map(Provenance::getSource)
			.sorted()
			.toList();
		assertEquals(List.of("crossref", "europepmc"), sources);
	}

	// ---- ISBN is addressable ----

	@Test
	public void isbnIdentifierIsRecognised() {
		Record book = new Stub(
				"9781402096327", "A Book", IdType.ISBN, List.of(), "crossref",
				"book", null
		);

		var merged = MergedRecord.from(book);

		assertEquals(IdType.ISBN, merged.getIdentifierType());
		assertEquals(
			"9781402096327",
			merged.getIdentifier()
				.orElse(null)
		);
	}

	// ---- what an agent actually sees ----

	@Test
	public void mcpRecordJsonIncludesTypePublisherAndProvenance()
			throws Exception {
		Record record = new Stub(
				"10.1/m", "Work", IdType.DOI, List.of(), "crossref",
				"journal-article", "BMJ"
		);

		var json = McpTools.recordJson(this.mapper, null, record);

		assertEquals(
			"article-journal",
			json.get("publicationType")
				.asText()
		);
		assertEquals(
			"BMJ",
			json.get("publisher")
				.asText()
		);
		assertEquals(
			"crossref",
			json.get("provenance")
				.get("source")
				.asText()
		);
	}

	@Test
	public void mcpRecordJsonOmitsAbsentFields() throws Exception {
		Record record = new Stub(
				"10.1/n", "Work", IdType.DOI, List.of(), null, null, null
		);

		var json = McpTools.recordJson(this.mapper, null, record);

		assertTrue(json.get("publicationType") == null);
		assertTrue(json.get("publisher") == null);
	}

	// --- stand-in so the model behaviour can be exercised offline ---

	static class Stub implements Record {
		private final String id;
		private final String title;
		private final IdType idType;
		private final List<Author> authors;
		private final Provenance provenance;
		private final String type;
		private final String publisher;

		Stub(
				String id, String title, IdType idType, List<Author> authors,
				String source, String nativeType, String publisher
		) {
			this.id = id;
			this.title = title;
			this.idType = idType;
			this.authors = authors;
			this.publisher = publisher;
			this.provenance = Provenance.of(source, nativeType);
			this.type = "crossref".equals(source) && "journal-article".equals(
				nativeType
			) ? "article-journal"
				: "crossref".equals(source) && "book".equals(nativeType) ? "book"
					: ("europepmc".equals(source) ? "article-journal" : nativeType);
		}

		@Override
		public Optional<String> getIdentifier() { return Optional.of(this.id); }
		@Override
		public IdType getIdentifierType() { return this.idType; }
		@Override
		public List<RecordReference> getOtherIdentifiers() { return List.of(); }
		@Override
		public List<Author> getAuthors() { return this.authors; }
		@Override
		public Stream<String> getLicenses() { return Stream.empty(); }
		@Override
		public Optional<String> getAbstract() { return Optional.empty(); }
		@Override
		public Optional<String> getTitle() { return Optional.of(this.title); }
		@Override
		public Optional<String> getJournal() {
			return Optional.of("Some Journal");
		}
		@Override
		public Optional<LocalDate> getDate() { return Optional.empty(); }
		@Override
		public Optional<URI> getPdfUri() { return Optional.empty(); }
		@Override
		public Optional<String> getPublicationType() {
			return Optional.ofNullable(this.type);
		}
		@Override
		public Optional<String> getPublisher() {
			return Optional.ofNullable(this.publisher);
		}
		@Override
		public Provenance getProvenance() { return this.provenance; }
	}
}
