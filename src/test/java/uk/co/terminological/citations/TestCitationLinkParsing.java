package uk.co.terminological.citations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import uk.co.terminological.bibliography.crossref.CrossRefReference;
import uk.co.terminological.bibliography.opencitations.OpenCitationsCitation;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * The two sources whose references arrived with neither an identifier nor a title.
 *
 * Measured on a real preprint (Crossref, 31 references): 17 carry only an
 * `unstructured` citation string, 15 only a DOI — and none carries an
 * `article-title`, which was the only thing the parser read. OpenCitations gives
 * PIDs and nothing else, and a v2 PID list with no `doi:` token produced a link with
 * a null identifier.
 *
 * [TESTS](/design/test-scripts/test-005-preprint-tests.md)
 */
public class TestCitationLinkParsing {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	public void aCrossrefReferenceWithOnlyAnUnstructuredStringStillHasATitle() throws Exception {
		CrossRefReference ref = new CrossRefReference(mapper.readTree(
				"{\"key\":\"ref1\",\"unstructured\":\"Wise J. Covid-19: New coronavirus variant is identified in UK. BMJ 2020;371:m4857\"}"));

		assertEquals("Wise J. Covid-19: New coronavirus variant is identified in UK. BMJ 2020;371:m4857",
				ref.getTitle().orElse(null));
		// and it does not claim to be a manuscript identifier it does not have
		assertTrue(ref.getIdentifier().isEmpty());
		assertEquals(IdType.UNK, ref.getIdentifierType());
	}

	@Test
	public void aCrossrefReferenceWithADoiAndNoTitleStillHasItsIdentifier() throws Exception {
		CrossRefReference ref = new CrossRefReference(mapper.readTree(
				"{\"key\":\"ref2\",\"doi-asserted-by\":\"publisher\",\"DOI\":\"10.1136/bmj.m4857\"}"));

		assertEquals("10.1136/bmj.m4857", ref.getIdentifier().orElse(null));
		assertEquals(IdType.DOI, ref.getIdentifierType());
		assertTrue(ref.getTitle().isEmpty());
	}

	@Test
	public void anOpenCitationsPidListWithoutADoiStillGivesAnIdentifier() throws Exception {
		// "omid:br/061603886611 openalex:W4384133663" has no doi token; the link used to
		// be built with a null identifier, so it was neither identifiable nor dedupable
		OpenCitationsCitation citation = new OpenCitationsCitation(mapper.readTree(
				"{\"oci\":\"061603886611-061301957054\","
						+ "\"citing\":\"omid:br/061603886611 openalex:W4384133663\","
						+ "\"cited\":\"omid:br/061301957054\"}"));

		assertTrue(citation.getCitingDoi().isEmpty());
		assertEquals("omid:br/061603886611", citation.getSource().getIdentifier().get().getIdentifier().orElse(null));
		assertEquals(IdType.UNK, citation.getSource().getIdentifier().get().getIdentifierType());
		assertEquals("omid:br/061301957054", citation.getTarget().getIdentifier().get().getIdentifier().orElse(null));
		assertTrue(citation.isComplete());
	}

	@Test
	public void anUnmatchedEuropePmcReferenceIsNamedByItsUnstructuredText() throws Exception {
		// Europe PMC returns the raw citation in `unstructuredInformation` with
		// `match: N` for a reference it could not resolve, and 17 of 31 rows for a real
		// preprint looked empty because only `title` was read
		var reference = new uk.co.terminological.bibliography.europepmc.EuropePMCReference(mapper.readTree(
				"{\"authorString\":\"AUTHOR UNKNOWN\",\"pubYear\":0,"
						+ "\"unstructuredInformation\":\"Investigation of novel SARS-CoV-2 variant: technical briefing 4.\","
						+ "\"citedOrder\":2,\"match\":\"N\"}"));

		assertEquals("Investigation of novel SARS-CoV-2 variant: technical briefing 4.",
				reference.getTitle().orElse(null));
		assertTrue(reference.getIdentifier().isEmpty());
	}

	@Test
	public void anOpenCitationsPidListWithADoiPrefersTheDoi() throws Exception {
		OpenCitationsCitation citation = new OpenCitationsCitation(mapper.readTree(
				"{\"citing\":\"omid:br/061603886611 doi:10.1016/j.jbi.2023.104436 openalex:W4384133663\","
						+ "\"cited\":\"omid:br/061301957054 doi:10.1136/bmjqs-2018-008370 pmid:30636200\"}"));

		assertEquals("10.1016/j.jbi.2023.104436", citation.getCitingDoi().orElse(null));
		RecordReference source = citation.getSource().getIdentifier().get();
		assertEquals(IdType.DOI, source.getIdentifierType());
		assertTrue(citation.isComplete());
		assertFalse(citation.getTarget().getIdentifier().get().getIdentifier().isEmpty());
	}
}
