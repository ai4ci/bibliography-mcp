package uk.co.terminological.bibliography.europepmc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_AUTHOR_SURNAMES;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_JOURNAL_ABBR;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_TITLE;
import static uk.co.terminological.bibliography.TestPaperFixtures.PMID;
import static uk.co.terminological.bibliography.TestPaperFixtures.PMCID;
import static uk.co.terminological.bibliography.TestPaperFixtures.PREPRINT_DOI;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.terminological.bibliography.record.PlainText;

/**
 * Tier-1 extraction tests (plan-007): recorded Europe PMC responses parsed through the
 * client's own list/result classes. The canonical MED record is asserted through the
 * whole Record interface; the citation and reference lists are asserted through the
 * identifier-or-name guarantee that unmatched rows rely on.
 */
public class TestEuropePMCExtraction {

	static EuropePMCListResult.Core medSearch;
	static EuropePMCListResult.Core pprSearch;
	static EuropePMCListResult.Citation citations;
	static EuropePMCListResult.Reference pmcReferences;
	static EuropePMCListResult.Reference pprReferences;
	static EuropePMCCoreResult med;

	@BeforeClass
	public static void parseTheRecordedCorpus() throws Exception {
		medSearch = new EuropePMCListResult.Core(node("fixtures/europepmc/search-med.json"));
		pprSearch = new EuropePMCListResult.Core(node("fixtures/europepmc/search-ppr.json"));
		citations = new EuropePMCListResult.Citation(node("fixtures/europepmc/citations-ppr.json"));
		pmcReferences = new EuropePMCListResult.Reference(node("fixtures/europepmc/references-pmc.json"));
		pprReferences = new EuropePMCListResult.Reference(node("fixtures/europepmc/references-ppr.json"));
		med = medSearch.getItems().findFirst().orElseThrow(() -> new IllegalStateException("no MED record in fixture"));
	}

	static JsonNode node(String fixture) throws Exception {
		try (InputStream is = ClassLoader.getSystemResourceAsStream(fixture)) {
			return new ObjectMapper().readTree(is);
		}
	}

	@Test
	public void theCanonicalMedicalRecordReadsThroughTheRecordInterface() {
		// EPMC/PubMed append a trailing period to article titles where other sources
		// do not: pinned raw, since Lite.getTitle() performs no normalisation
		assertEquals(EXPECTED_TITLE + ".", med.getTitle().orElse("<no title>"));
		assertEquals(PMID, med.getPMID().orElse("<no PMID>"));
		assertTrue("the PMCID should surface", med.getPMCID().orElse("<none>").contains(PMCID.replace("PMC", "")));
		assertEquals(uk.co.terminological.bibliography.TestPaperFixtures.PUBLISHED_DOI,
			med.getDOI().orElse("<no DOI>"));
		assertTrue("a publication date is expected", med.getDate().isPresent());
		assertEquals(EXPECTED_AUTHOR_SURNAMES.length, med.getAuthors().size());
		assertEquals(EXPECTED_AUTHOR_SURNAMES[0], med.getAuthors().get(0).getLastName());
	}

	@Test
	public void coreResultsCarryTheMedlineAbbreviationFromJournalInfo() {
		// no getter exists for it — the field is pinned through the raw node
		String abbr = med.getRaw().at("/journalInfo/journal/medlineAbbreviation").asText("");
		assertEquals(EXPECTED_JOURNAL_ABBR, abbr);
	}

	@Test
	public void aPreprintCoreRecordIsFoundByIdItsOwnDoi() {
		EuropePMCCoreResult ppr = pprSearch.getItems().findFirst()
			.orElseThrow(() -> new IllegalStateException("no PPR record in fixture"));
		// PPR titles keep <i> species markup from the preprint server; PlainText is
		// the layer that normalises it back to the corpus title
		assertEquals(EXPECTED_TITLE, PlainText.of(ppr.getTitle().orElse("<no title>")));
		assertEquals(PREPRINT_DOI, ppr.getRaw().path("doi").asText(""));
		assertTrue("the list must report a hit", pprSearch.hitCount().orElse(0L) >= 1L);
	}

	@Test
	public void everyCitationRowNamesItsCitingWork() {
		List<EuropePMCCitation> rows = citations.getItems().collect(Collectors.toList());
		assertTrue("a well cited preprint should have citations, got " + rows.size(), rows.size() > 0);
		for (EuropePMCCitation row : rows) {
			assertTrue("citation row has no title", row.getTitle().isPresent());
		}
	}

	@Test
	public void everyReferenceRowIsNamedByIdentifierTitleOrUnstructuredText() {
		List<EuropePMCReference> rows = pmcReferences.getItems().collect(Collectors.toList());
		assertTrue("the PMC record should have references, got " + rows.size(), rows.size() > 10);
		int withIds = 0;
		for (EuropePMCReference row : rows) {
			boolean hasId = row.getRaw().path("id").asText("").length() > 0;
			assertTrue("a reference row carries no name of any kind",
				hasId || row.getTitle().isPresent()
					|| row.getRaw().path("unstructuredInformation").asText("").length() > 0);
			withIds += hasId ? 1 : 0;
		}
		assertTrue("some references should have matched indexed records", withIds > 0);
	}

	@Test
	public void preprintReferencesAreEmptyAsTheApiDocuments() {
		assertEquals(0L, pprReferences.hitCount().orElse(-1L).longValue());
		assertEquals(0, pprReferences.getItems().count());
	}
}
