package uk.co.terminological.bibliography.api.zotero;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * feat-008: comparing a Zotero item against what the APIs say, so the agent can decide. The tool reports; it never
 * picks a winner.
 *
 * [TESTS](/design/test-scripts/test-004-metadata-cleanup-tests.md)
 */
public class TestMetadataComparison {

	private final ObjectMapper mapper = new ObjectMapper();

	private JsonNode item(String json) throws Exception {
		var node = this.mapper.readTree(json);
		return node.has("data") ? node.get("data") : node;
	}

	@Test
	public void equalValuesAgreeAfterNormalisation() throws Exception {
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K1\",\"itemType\":\"journalArticle\",\"title\":\"Health data: a review\","
						+ "\"publisher\":\"BMJ\",\"DOI\":\"10.1136/EXAMPLE\"}"
			),
			List.of(
				record(
					"crossref",
					"Health Data - a Review",
					"BMJ",
					"10.1136/example",
					null,
					null
				)
			)
		);

		assertEquals(
			"agree",
			report.get("classification")
				.asText()
		);
		assertEquals(
			"agree",
			report.get("fields")
				.get("title")
				.get("class")
				.asText()
		);
		assertEquals(
			"agree",
			report.get("fields")
				.get("publisher")
				.get("class")
				.asText()
		);
		assertEquals(
			"agree",
			report.get("fields")
				.get("DOI")
				.get("class")
				.asText()
		);
	}

	@Test
	public void anEmptyZoteroFieldThatAnApiCanFillIsFillable() throws Exception {
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K2\",\"itemType\":\"journalArticle\",\"title\":\"T\"}"
			),
			List.of(
				record(
					"europepmc",
					"T",
					null,
					null,
					"An abstract Europe PMC has",
					null
				)
			)
		);

		assertEquals(
			"gaps",
			report.get("classification")
				.asText()
		);
		var abstractField = report.get("fields")
			.get("abstractNote");
		assertEquals(
			"fillable",
			abstractField.get("class")
				.asText()
		);
		assertEquals(
			"An abstract Europe PMC has",
			abstractField.get("apis")
				.get("europepmc")
				.asText()
		);
		assertTrue(abstractField.get("zotero") == null);
	}

	@Test
	public void differingValuesAreAConflictAndBothSidesAreShown()
			throws Exception {
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K3\",\"itemType\":\"journalArticle\",\"title\":\"T\","
						+ "\"publisher\":\"BMJ Publishing Group\",\"date\":\"2019\"}"
			),
			List.of(
				record("crossref", "T", "BMJ", null, null, LocalDate.of(2019, 1, 1))
			)
		);

		assertEquals(
			"conflicts",
			report.get("classification")
				.asText()
		);
		var publisher = report.get("fields")
			.get("publisher");
		assertEquals(
			"conflict",
			publisher.get("class")
				.asText()
		);
		assertEquals(
			"BMJ Publishing Group",
			publisher.get("zotero")
				.asText()
		);
		assertEquals(
			"BMJ",
			publisher.get("apis")
				.get("crossref")
				.asText()
		);
		// year-only against a full date is a judgement call, not a silent upgrade
		assertEquals(
			"conflict",
			report.get("fields")
				.get("date")
				.get("class")
				.asText()
		);
		assertEquals(
			"2019",
			report.get("fields")
				.get("date")
				.get("zotero")
				.asText()
		);
		assertEquals(
			"2019-01-01",
			report.get("fields")
				.get("date")
				.get("apis")
				.get("crossref")
				.asText()
		);
	}

	@Test
	public void severalApisAreAttributedSeparately() throws Exception {
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K4\",\"itemType\":\"journalArticle\",\"title\":\"T\"}"
			),
			List.of(
				record("crossref", "T", null, null, "Abstract from CrossRef", null),
				record(
					"europepmc",
					"T",
					null,
					null,
					"Abstract from Europe PMC",
					null
				)
			)
		);

		var apis = report.get("fields")
			.get("abstractNote")
			.get("apis");
		assertEquals(
			"Abstract from CrossRef",
			apis.get("crossref")
				.asText()
		);
		assertEquals(
			"Abstract from Europe PMC",
			apis.get("europepmc")
				.asText()
		);
	}

	@Test
	public void aFieldNoApiKnowsIsReportedAsZoteroOnly() throws Exception {
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K5\",\"itemType\":\"journalArticle\",\"title\":\"T\","
						+ "\"publisher\":\"A small press\"}"
			),
			List.of(record("crossref", "T", null, null, null, null))
		);

		assertEquals(
			"zotero-only",
			report.get("fields")
				.get("publisher")
				.get("class")
				.asText()
		);
		assertEquals(
			"agree",
			report.get("classification")
				.asText()
		); // nothing to change
	}

	@Test
	public void markupIsNotContent() throws Exception {
		// CrossRef returns abstracts as JATS XML; the tags are not a disagreement and
		// must not be written into abstractNote either
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K7\",\"itemType\":\"journalArticle\",\"title\":\"T\","
						+ "\"abstractNote\":\"We studied things &amp; found more.\"}"
			),
			List.of(
				record(
					"crossref",
					"T",
					null,
					null,
					"<jats:title>ABSTRACT</jats:title><jats:p>We studied things &amp; found more.</jats:p>",
					null
				)
			)
		);

		var field = report.get("fields")
			.get("abstractNote");
		assertEquals(
			"agree",
			field.get("class")
				.asText()
		);
		// and the value surfaced for the agent is the clean text, not the markup
		assertEquals(
			"We studied things & found more.",
			field.get("apis")
				.get("crossref")
				.asText()
		);
	}

	@Test
	public void theContainerIsReportedUnderTheFieldNameZoteroUses()
			throws Exception {
		// "container" is not a Zotero field: an article's is publicationTitle, a
		// chapter's is bookTitle. The report has to name the field an update can use.
		var article = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K8\",\"itemType\":\"journalArticle\",\"title\":\"T\","
						+ "\"publicationTitle\":\"Gaceta Sanitaria\"}"
			),
			List.of(containerRecord("crossref", "Gaceta Sanitaria"))
		);
		assertEquals(
			"agree",
			article.get("fields")
				.get("publicationTitle")
				.get("class")
				.asText()
		);
		assertTrue(
			article.get("fields")
				.get("container") == null
		);

		var chapter = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K9\",\"itemType\":\"bookSection\",\"title\":\"T\","
						+ "\"bookTitle\":\"The containing book\"}"
			),
			List.of(containerRecord("crossref", "The containing book"))
		);
		assertEquals(
			"agree",
			chapter.get("fields")
				.get("bookTitle")
				.get("class")
				.asText()
		);
	}

	@Test
	public void placeholderAuthorNamesAreIgnored() throws Exception {
		// Unpaywall says "Unknown" for every author when it has no author data; that
		// must not read as a disagreement with every item it appears on
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K11\",\"itemType\":\"journalArticle\",\"title\":\"T\","
						+ "\"creators\":[{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"creatorType\":\"author\"}]}"
			),
			List.of(
				recordWithAuthors("unpaywall", "T", List.of("Unknown")),
				recordWithAuthors("crossref", "T", List.of("Lovelace"))
			)
		);

		var creators = report.get("fields")
			.get("creators");
		assertEquals(
			"agree",
			creators.get("class")
				.asText()
		);
		assertTrue(
			creators.get("apiSurnames")
				.get("unpaywall") == null
		);
	}

	@Test
	public void aRealDifferenceInAuthorsIsStillAConflict() throws Exception {
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K12\",\"itemType\":\"journalArticle\",\"title\":\"T\","
						+ "\"creators\":[{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"creatorType\":\"author\"}]}"
			),
			List.of(
				recordWithAuthors("crossref", "T", List.of("Lovelace", "Babbage"))
			)
		);

		assertEquals(
			"conflict",
			report.get("fields")
				.get("creators")
				.get("class")
				.asText()
		);
	}

	@Test
	public void creatorsCarryANoteBecauseAnUpdateNeedsTheArrayItself()
			throws Exception {
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K10\",\"itemType\":\"journalArticle\",\"title\":\"T\"}"
			),
			List.of(recordWithAuthors("crossref", "T", List.of("Lovelace")))
		);

		assertEquals(
			"fillable",
			report.get("fields")
				.get("creators")
				.get("class")
				.asText()
		);
		assertTrue(
			report.get("fields")
				.get("creators")
				.get("note")
				.asText()
				.contains("array")
		);
	}

	@Test
	public void markupAlreadyInTheLibraryIsFlaggedRatherThanForgiven()
			throws Exception {
		// both sides agree once markup is ignored, so without a flag the marked-up
		// value would sit in the library looking like an agreement
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K13\",\"itemType\":\"journalArticle\",\"title\":\"T\","
						+ "\"abstractNote\":\"<jats:p>We studied things.</jats:p>\"}"
			),
			List
				.of(record("crossref", "T", null, null, "We studied things.", null))
		);

		var abstractField = report.get("fields")
			.get("abstractNote");
		assertEquals(
			"agree",
			abstractField.get("class")
				.asText()
		);
		assertTrue(
			abstractField.get("zoteroHasMarkup")
				.asBoolean()
		);
		assertEquals(
			1,
			report.get("fieldsWithMarkup")
				.asInt()
		);
		// and the clean value is on offer to replace it with
		assertEquals(
			"We studied things.",
			abstractField.get("apis")
				.get("crossref")
				.asText()
		);
	}

	@Test
	public void aPreprintsContainerIsItsRepository() throws Exception {
		// a Zotero preprint holds the server in `repository`, not publicationTitle, so
		// comparing against publicationTitle reported the container as a gap
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K14\",\"itemType\":\"preprint\",\"title\":\"T\",\"repository\":\"medRxiv\"}"
			),
			List.of(containerRecord("medrxiv", "medRxiv"))
		);

		assertEquals(
			"agree",
			report.get("fields")
				.get("repository")
				.get("class")
				.asText()
		);
		assertTrue(
			report.get("fields")
				.get("publicationTitle") == null
		);
	}

	@Test
	public void creatorsAreComparedBySurnameAndCountOnly() throws Exception {
		var report = MetadataComparison.compare(
			this.mapper,
			this.item(
				"{\"key\":\"K6\",\"itemType\":\"journalArticle\",\"title\":\"T\","
						+ "\"creators\":[{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"creatorType\":\"author\"}]}"
			),
			List.of(recordWithAuthors("crossref", "T", List.of("Lovelace")))
		);

		assertEquals(
			"agree",
			report.get("fields")
				.get("creators")
				.get("class")
				.asText()
		);
		assertEquals(
			"1",
			report.get("fields")
				.get("creators")
				.get("zotero")
				.asText()
		);
	}

	// ---- stand-ins ----

	private static Record record(
			String source, String title, String publisher, String doi,
			String abstractText, LocalDate date
	) {
		return stub(source, title, publisher, doi, abstractText, date, List.of());
	}

	private static Record containerRecord(String source, String container) {
		return new Record() {
			@Override
			public Optional<String> getIdentifier() { return Optional.empty(); }
			@Override
			public IdType getIdentifierType() { return IdType.UNK; }
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
			public Optional<String> getTitle() { return Optional.of("T"); }
			@Override
			public Optional<String> getJournal() { return Optional.of(container); }
			@Override
			public Optional<LocalDate> getDate() { return Optional.empty(); }
			@Override
			public Optional<java.net.URI> getPdfUri() { return Optional.empty(); }
			@Override
			public Provenance getProvenance() {
				// the source is known, the kind of work it called this is not
				return Provenance.placeholder(source);
			}
		};
	}

	private static Record recordWithAuthors(
			String source, String title, List<String> surnames
	) {
		return stub(source, title, null, null, null, null, surnames);
	}

	private static Record stub(
			String source, String title, String publisher, String doi,
			String abstractText, LocalDate date, List<String> surnames
	) {
		return new Record() {
			@Override
			public Optional<String> getIdentifier() {
				return Optional.ofNullable(doi);
			}
			@Override
			public IdType getIdentifierType() {
				return doi == null ? IdType.UNK : IdType.DOI;
			}
			@Override
			public List<RecordReference> getOtherIdentifiers() {
				return List.of();
			}
			@Override
			public List<Author> getAuthors() {
				return surnames.stream()
					.map(surname -> (Author) new Author() {
						@Override
						public Optional<String> getORCID() { return Optional
							.empty(); }
						@Override
						public Optional<String> getFirstName() {
							return Optional.of("A");
						}
						@Override
						public String getLastName() { return surname; }
						@Override
						public Optional<String> getInitials() {
							return Optional.empty();
						}
						@Override
						public java.util.stream.Stream<String> getAffiliations() {
							return java.util.stream.Stream.empty();
						}
					})
					.toList();
			}
			@Override
			public java.util.stream.Stream<String> getLicenses() {
				return java.util.stream.Stream.empty();
			}
			@Override
			public Optional<String> getAbstract() {
				return Optional.ofNullable(abstractText);
			}
			@Override
			public Optional<String> getTitle() {
				return Optional.ofNullable(title);
			}
			@Override
			public Optional<String> getJournal() { return Optional.empty(); }
			@Override
			public Optional<LocalDate> getDate() {
				return Optional.ofNullable(date);
			}
			@Override
			public Optional<java.net.URI> getPdfUri() { return Optional.empty(); }
			@Override
			public Optional<String> getPublisher() {
				return Optional.ofNullable(publisher);
			}
			@Override
			public Provenance getProvenance() {
				return Provenance.of(source, "journal-article");
			}
		};
	}
}
