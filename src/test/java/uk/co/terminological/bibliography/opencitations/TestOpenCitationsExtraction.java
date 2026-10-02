package uk.co.terminological.bibliography.opencitations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static uk.co.terminological.bibliography.TestPaperFixtures.CITATION_RICH_DOI;
import static uk.co.terminological.bibliography.TestPaperFixtures.CITATION_RICH_VOR_DOI;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.terminological.bibliography.record.IdType;

/**
 * Tier-1 extraction tests (plan-007): recorded OpenCitations Index v2 responses parsed
 * through the client's own list/citation classes. The corpus article's identifiers are
 * embedded in multi-pid handle strings ("omid:br/... doi:10.... openalex:...") — the
 * DOI extraction through that noise is the point of this test.
 */
public class TestOpenCitationsExtraction {

	static OpenCitationsListResult citations;
	static OpenCitationsListResult references;

	@BeforeClass
	public static void parseTheRecordedCorpus() throws Exception {
		citations = list("fixtures/opencitations/citations-published.json");
		references = list("fixtures/opencitations/references-cited.json");
	}

	static OpenCitationsListResult list(String fixture) throws Exception {
		try (InputStream is = ClassLoader.getSystemResourceAsStream(fixture)) {
			return new OpenCitationsListResult(new ObjectMapper().readTree(is));
		}
	}

	@Test
	public void everyCitationRowExtractsBothDoisFromItsHandles() {
		List<OpenCitationsCitation> rows = citations.getCitations().collect(Collectors.toList());
		assertTrue("the corpus article should be cited, got " + rows.size(), rows.size() > 10);
		for (OpenCitationsCitation row : rows) {
			// incoming citations are aggregated against the version of record, even
			// though the recorded row also carries the preprint'somid handle
			assertEquals("every recorded citation cites the corpus article's version of record",
				CITATION_RICH_VOR_DOI, row.getCitedDoi().orElse("<none>"));
			// OpenCitations names works by handle and nothing else — a citing work
			// without a DOI still surfaces its omid/pmid handle as an UNK identifier
			assertTrue("the citing work should surface some identifier",
				row.getSource().getIdentifier().isPresent());
		}
		assertTrue(
			"the citing works should be a variety of papers",
			rows.stream().map(OpenCitationsCitation::getCitingDoi).distinct().count() > 5
		);
	}

	@Test
	public void citationRowsImplementTheCitationLinkContract() {
		OpenCitationsCitation row = citations.getCitations().findFirst().orElseThrow();
		assertEquals(IdType.DOI, row.getSource().getIdentifier().orElseThrow().getIdentifierType());
		assertEquals(CITATION_RICH_VOR_DOI,
			row.getTarget().getIdentifier().orElseThrow().getIdentifier().orElse("<none>"));
		assertTrue("the oci should surface as the citation id", row.getCitationId().isPresent());
	}

	@Test
	public void referencesPointAwayFromTheCorpusArticleAsItsOwnCitations() {
		List<OpenCitationsCitation> rows = references.getCitations().collect(Collectors.toList());
		assertTrue("the corpus article should have references, got " + rows.size(), rows.size() > 10);
		for (OpenCitationsCitation row : rows) {
			assertEquals("every recorded reference is cited by the corpus article",
				CITATION_RICH_DOI, row.getCitingDoi().orElse("<none>"));
		}
	}
}
