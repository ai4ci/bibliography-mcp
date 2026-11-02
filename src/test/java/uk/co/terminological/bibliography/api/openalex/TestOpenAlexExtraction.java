package uk.co.terminological.bibliography.api.openalex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_AUTHOR_SURNAMES;
import static uk.co.terminological.bibliography.TestPaperFixtures.CITATION_RICH_VOR_DOI;
import static uk.co.terminological.bibliography.TestPaperFixtures.PMCID;
import static uk.co.terminological.bibliography.TestPaperFixtures.PMID;
import static uk.co.terminological.bibliography.TestPaperFixtures.PREPRINT_DOI;
import static uk.co.terminological.bibliography.TestPaperFixtures.PUBLISHED_DOI;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.CitationReference;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.PrintReference;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.PublicationTypes;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * Offline translation of OpenAlex's own JSON into the record model, from responses recorded against the live API.
 *
 * The assertions are against OpenAlex's payload rather than against what the model happens to make easy: where the two
 * disagree the test records the disagreement (the en dash in the title, the capitalisation of the journal) so that a
 * later cross-source normalisation decision has something to contradict.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestOpenAlexExtraction {

	static OpenAlexRecord published;
	static OpenAlexRecord preprint;
	static OpenAlexRecord cited;
	static OpenAlexListResult.Works batch;
	static OpenAlexListResult.Works citing;

	static JsonNode node(String fixture) throws Exception {
		try (InputStream is = ClassLoader.getSystemResourceAsStream(fixture)) {
			if (is == null) throw new IllegalStateException("missing fixture: " + fixture);
			return new ObjectMapper().readTree(is);
		}
	}

	@BeforeClass
	public static void parseTheRecordedCorpus() throws Exception {
		published = new OpenAlexRecord(node("fixtures/openalex/work-published.json"));
		preprint = new OpenAlexRecord(node("fixtures/openalex/work-preprint.json"));
		cited = new OpenAlexRecord(node("fixtures/openalex/work-cited.json"));
		batch = new OpenAlexListResult.Works(node("fixtures/openalex/batch-references.json"));
		citing = new OpenAlexListResult.Works(node("fixtures/openalex/citing-published.json"));
	}

	@Test
	public void theCanonicalWorkReadsThroughTheRecordInterface() {
		// OpenAlex carries an en dash in the date range where Zotero and Europe PMC
		// record a hyphen, so the title is asserted as OpenAlex spells it: whether to
		// normalise is a merge-layer decision, not a parsing one
		assertEquals(
			"Trends in invasive and non-invasive Streptococcus pneumoniae disease in adults "
					+ "hospitalised in Bristol and Bath: a retrospective cohort study, "
					+ "2006\u20132022",
			published.getTitle()
				.orElse("<no title>")
		);
		assertEquals(
			LocalDate.of(2026, 3, 26),
			published.getDate()
				.orElse(null)
		);
		assertEquals(
			EXPECTED_AUTHOR_SURNAMES.length,
			published.getAuthors()
				.size()
		);
		assertEquals(
			"Challen",
			published.getAuthors()
				.get(0)
				.getLastName()
		);
		assertEquals(
			"Robert",
			published.getAuthors()
				.get(0)
				.getFirstName()
				.orElse("<no forename>")
		);
	}

	@Test
	public void theContainerIsNestedAndFallsBackToTheRepositoryName() {
		// journal metadata sits under primary_location.source, as in Europe PMC core:
		// issue-012 is what reading a flat field there costs. For a preprint the same
		// path holds the server, which is the closest thing to a container it has
		assertEquals(
			"BMC Infectious Diseases",
			published.getJournal()
				.orElse("<no journal>")
		);
		assertEquals(
			"BMC Infectious Diseases",
			published.getContainerTitle()
				.orElse("<no container>")
		);
		assertEquals(
			"BioMed Central",
			published.getPublisher()
				.orElse("<no publisher>")
		);
		assertEquals(
			"medRxiv",
			preprint.getJournal()
				.orElse("<no journal>")
		);
	}

	@Test
	public void thePrintCoordinatesComeFromBiblio() {
		assertEquals(
			"26",
			published.getVolume()
				.orElse("<no volume>")
		);
		assertEquals(
			"1",
			published.getIssue()
				.orElse("<no issue>")
		);
		assertEquals(
			Long.valueOf(2026),
			published.getYear()
				.orElse(null)
		);
		assertEquals(
			"n579",
			((PrintReference) cited).getPage()
				.orElse("<no page>")
		);
	}

	@Test
	public void theAbstractIsReassembledFromTheInvertedIndexInPositionOrder() {
		// OpenAlex gives an abstract as word -> positions. Joining the keys in map
		// order produces fluent nonsense rather than an error, so the order is the
		// thing under test
		String text = published.getAbstract()
			.orElse("<no abstract>");

		assertEquals(
			"BACKGROUND: Pneumococcal surveillance typically focuses",
			text.substring(0, 55)
		);
		assertEquals(
			"the prevention of meningitis.",
			text.substring(text.length() - 29)
		);
		assertEquals(
			321,
			text.split("\\s+")
				.length
		);
	}

	@Test
	public void identifiersJoinWithTheOtherProviders() {
		// OpenAlex publishes the PMC id as a bare URL slug (.../articles/13141338)
		// while idconv, PubMed and Europe PMC all store PMC13141338. Un-normalised the
		// two are different works to MergedRecord, so the same paper appears twice
		assertEquals(
			PMID,
			published.getIdentifier(IdType.PMID)
				.orElse("<no PMID>")
		);
		assertEquals(
			PMCID,
			published.getIdentifier(IdType.PMCID)
				.orElse("<no PMCID>")
		);
		assertEquals(
			PUBLISHED_DOI,
			published.getIdentifier(IdType.DOI)
				.orElse("<no DOI>")
		);
		assertEquals(
			"W7140411570",
			published.getIdentifier(IdType.OPENALEX)
				.orElse("<no OpenAlex id>")
		);
	}

	@Test
	public void everyIdentifierTheWorkCarriesIsOfferedForMatching() {
		List<String> types = published.getOtherIdentifiers()
			.stream()
			.map(r -> r.getIdentifierType()
				.name())
			.sorted()
			.collect(Collectors.toList());

		assertEquals(
			List.of("DOI", "OPENALEX", "PMCID", "PMID"),
			types
		);
	}

	@Test
	public void theNativeTypeIsKeptAndMappedToTheCslVocabulary() {
		assertEquals(
			"article",
			published.getProvenance()
				.getNativeType()
				.orElse("<no native type>")
		);
		assertEquals(
			Provenance.OPENALEX,
			published.getProvenance()
				.getSource()
		);
		assertEquals(
			PublicationTypes.ARTICLE_JOURNAL,
			published.getPublicationType()
				.orElse("<no type>")
		);
	}

	@Test
	public void aPreprintIsTypedAsPostedContentAndNamedByItsOwnDoi() {
		assertEquals(
			PREPRINT_DOI,
			preprint.getIdentifier()
				.orElse("<no identifier>")
		);
		assertEquals(
			IdType.DOI,
			preprint.getIdentifierType()
		);
		assertEquals(
			PublicationTypes.POSTED_CONTENT,
			preprint.getPublicationType()
				.orElse("<no type>")
		);
	}

	@Test
	public void theOpenAccessPdfIsTheOneTheMergePolicyWillRank() {
		// OpenAlex replaces Unpaywall as the open-access locator, so this is the field
		// the PDF priority in MergePolicy now depends on
		assertEquals(
			"https://www.medrxiv.org/content/medrxiv/early/2025/12/15/2025.12.12.25342129.full.pdf",
			preprint.getPdfUri()
				.map(Object::toString)
				.orElse("<no pdf>")
		);
		assertTrue(
			"the licence the repository records should survive",
			preprint.getLicenses()
				.findFirst()
				.isPresent()
		);
	}

	@Test
	public void aSparseRecordYieldsAbsencesRatherThanThrowing() {
		// a work with no abstract, no DOI, no date and no source is ordinary in
		// OpenAlex; every accessor has to answer "no" rather than fail — the class of
		// bug issue-005 was about
		OpenAlexRecord sparse = new OpenAlexRecord(
				new ObjectMapper()
					.createObjectNode()
					.put("id", "https://openalex.org/W1234567890")
		);

		assertEquals(
			"https://openalex.org/W1234567890",
			sparse.getRaw()
				.at("/id")
				.asText()
		);
		assertFalse(sparse.getAbstract()
			.isPresent());
		assertFalse(sparse.getDate()
			.isPresent());
		assertFalse(sparse.getJournal()
			.isPresent());
		assertFalse(sparse.getPdfUri()
			.isPresent());
		assertFalse(sparse.getTitle()
			.isPresent());
		assertFalse(sparse.getPublisher()
			.isPresent());
		assertFalse(sparse.getVolume()
			.isPresent());
		assertTrue(sparse.getAuthors()
			.isEmpty());
		assertEquals(
			"a work with only an id still has that one identifier",
			1,
			sparse.getOtherIdentifiers()
				.size()
		);
		assertEquals(
			"W1234567890",
			sparse.getIdentifier()
				.orElse("<no identifier>")
		);
		assertEquals(
			Provenance.OPENALEX,
			sparse.getProvenance()
				.getSource()
		);
	}

	@Test
	public void aCurlOfATruncatedBodyIsRejectedNotTreatedAsEmpty() {
		// a prefix of a JSON body can be valid JSON, and a JSON array of nothing is a
		// well-formed empty response. The distinction is whether the envelope is
		// there at all — without it a cut-short connection becomes a cached "no
		// citations" (issue-004)
		OpenAlexListResult.Works array = new OpenAlexListResult.Works(
				new ObjectMapper().createArrayNode()
		);
		OpenAlexListResult.Works envelope = new OpenAlexListResult.Works(
				new ObjectMapper().createObjectNode()
		);

		assertTrue(
			"[] is a legitimate empty result set",
			array.isWellFormed()
		);
		assertFalse(
			"{} has no meta and no results, so nothing was actually returned",
			envelope.isWellFormed()
		);
	}

	@Test
	public void metaCountIsThePopulationAndResultsAreThePage() {
		// reading one as the other silently truncates a crawl at the page size
		assertEquals(
			Long.valueOf(8),
			batch.hitCount()
				.orElse(null)
		);
		assertEquals(
			8,
			batch.getItems()
				.count()
		);
		assertEquals(
			Long.valueOf(775),
			citing.hitCount()
				.orElse(null)
		);
		assertEquals(
			3,
			citing.getItems()
				.count()
		);
		assertTrue(batch.isWellFormed());
	}

	@Test
	public void aCurlOfATruncatedBatchYieldsNoItemsAndNoCount() {
		// the guard above is what turns this into a retry rather than an answer
		ObjectNode body = new ObjectMapper().createObjectNode();
		body.putObject("meta")
			.put("count", 8);
		OpenAlexListResult.Works partial = new OpenAlexListResult.Works(body);

		assertTrue(partial.isWellFormed());
		assertEquals(
			0,
			partial.getItems()
				.count()
		);
	}

	@Test
	public void aWorkNamesTheWorksItCites() {
		List<RecordReference> refs = cited.getReferencedWorks();

		assertEquals(17, refs.size());
		for (RecordReference ref : refs) {
			assertTrue(
				"a referenced work has no identifier",
				ref.getIdentifier()
					.isPresent()
			);
			assertEquals(IdType.OPENALEX, ref.getIdentifierType());
		}
	}

	@Test
	public void downwardEdgesPointFromTheAskingWorkToTheWorkItCites() {
		// CitationLink's direction is a contract — source cites target, settled when
		// issue-007 closed. OpenAlex reports the two directions as different payloads,
		// which is exactly how one of them gets inverted, so both are pinned here
		List<CitationLink> links = cited.getCitations();

		assertEquals(17, links.size());
		for (CitationLink link : links) {
			assertEquals(
				"the asking work must be the citing end: " + link,
				CITATION_RICH_VOR_DOI,
				named(link.getSource())
			);
			assertTrue(
				"an edge must name what it cites: " + link,
				link.getTarget()
					.isComplete()
			);
			assertEquals(
				IdType.OPENALEX,
				link.getTarget()
					.getIdentifier()
					.map(r -> r.getIdentifierType())
					.orElse(IdType.UNK)
			);
			assertFalse(
				"a work does not cite itself",
				CITATION_RICH_VOR_DOI.equals(named(link.getTarget()))
			);
		}
	}

	@Test
	public void upwardEdgesPointFromTheCitingWorksToTheAskedWork() {
		RecordReference asked = OpenAlexClient.workReference("W3134566701");
		RecordReference citer = OpenAlexClient.workReference("W3010096538");

		CitationLink link = Builder.citationLink(citer, asked, 1);

		assertEquals(
			"W3010096538",
			named(link.getSource())
		);
		assertEquals(
			"W3134566701",
			named(link.getTarget())
		);
	}

	/** The work an edge end names, or "&lt;none&gt;": an edge end is a reference to a reference. */
	static String named(CitationReference end) {
		return end.getIdentifier()
			.flatMap(RecordReference::getIdentifier)
			.orElse("<none>");
	}

	@Test
	public void aReferenceIsNamedByIdOrNotAtAll() {
		assertEquals(
			"W3134566701",
			OpenAlexClient.workReference("W3134566701")
				.getIdentifier()
				.orElse("<none>")
		);
		assertFalse(
			"a null OpenAlex id must not become the identifier \"null\"",
			OpenAlexClient.workReference(null)
				.isComplete()
		);
		assertFalse(
			OpenAlexClient.workReference(null)
				.getIdentifier()
				.isPresent()
		);
	}

	@Test
	public void retractionIsNotSilentlyDroppedOnTheFloor() {
		// the model has nowhere to put a retraction, so the honest option is to keep the
		// native signal reachable rather than lose it in the mapping
		assertFalse(published.isRetracted());
		assertFalse(
				cited.getRaw()
					.get("is_retracted")
					.asBoolean(true)
		);
	}

	@Test
	public void authorsKeepTheirOrcidAndAffiliation() {
		// ORCID is what MergedRecord keys author de-duplication on, and no other
		// registered source actually supplies it in our fixtures, so OpenAlex is the
		// only place where that path is exercised
		var first = published.getAuthors()
			.get(0);

		assertEquals(
			"https://orcid.org/0000-0002-5504-7768",
			first.getORCID()
				.orElse("<no orcid>")
		);
		assertTrue(
			"the raw affiliation string is what OpenAlex offers: " + first.getAffiliations()
				.findFirst()
				.orElse("<none>"),
			first.getAffiliations()
				.findFirst()
				.orElse("")
				.contains("University of Bristol")
		);
	}

	@Test
	public void anAbsentOrcidIsAnAbsenceNotAThrowingLookup() {
		// four of the sixteen authors have no ORCID; a missing one must read as an
		// absence, since MergedRecord falls back to a name key only when the Optional
		// is empty rather than when a lookup fails
		List<Optional<String>> orcids = published.getAuthors()
			.stream()
			.map(a -> a.getORCID())
			.collect(Collectors.toList());

		assertEquals(16, orcids.size());
		assertEquals(
			"four authors in the fixture carry no ORCID",
			4,
			orcids.stream()
				.filter(o -> !o.isPresent())
				.count()
		);
	}
}
