package uk.co.terminological.bibliography;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import org.junit.Test;

import uk.co.terminological.bibliography.CachingApiClient;
import uk.co.terminological.bibliography.api.europepmc.EuropePMCListResult;
import uk.co.terminological.bibliography.api.opencitations.OpenCitationsCitation;
import uk.co.terminological.bibliography.api.pmcidconv.PMCIDResult;

// [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
// [TESTS](/design/implementation/issues/issue-001-non-doi-citation-links-dropped.md)
// [TESTS](/design/implementation/issues/issue-002-third-party-api-endpoint-drift.md)
// [TESTS](/design/implementation/issues/issue-004-truncated-responses-are-cached-as-empty.md)
public class TestApiDriftParsing {

	private final ObjectMapper mapper = new ObjectMapper()
		.registerModule(new Jdk8Module());

	@Test
	public void pmcidConvParsesCurrentResponseShape() throws Exception {
		// recorded idconv response (2026-09): hyphenated response-date, request as an
		// object, numeric pmid, new requested-id field — all of which broke strict parsing
		String json = "{\"status\":\"ok\",\"response-date\":\"2026-09-22 11:53:35\","
				+ "\"request\":{\"warnings\":[],\"format\":\"json\",\"idtype\":\"pmid\","
				+ "\"ids\":[\"30636200\"],\"tool\":\"test\",\"email\":\"a@b.c\"},"
				+ "\"records\":[{\"doi\":\"10.1136/bmjqs-2018-008370\",\"pmcid\":\"PMC6560460\","
				+ "\"pmid\":30636200,\"requested-id\":\"30636200\"}]}";

		PMCIDResult result = mapper.readValue(json, PMCIDResult.class);

		assertEquals("ok", result.status.orElse(null));
		assertEquals(1, result.records.size());
		assertEquals("PMC6560460", result.records.get(0).pmcid.orElse(null));
		assertEquals("30636200", result.records.get(0).pmid.orElse(null));
		assertEquals(
			"10.1136/bmjqs-2018-008370",
			result.records.get(0).doi.orElse(null)
		);
	}

	@Test
	public void aTruncatedSearchResponseIsRecognisedAsBroken() throws Exception {
		// issue-004: a partial 2xx body parses cleanly and yields an empty item
		// stream, so without an explicit shape check the client cannot tell "the
		// record is not there" from "the response was broken".
		EuropePMCListResult.Core truncated = new EuropePMCListResult.Core(
				mapper.readTree("{\"version\":\"6.9\"}")
		);

		assertEquals(
			0,
			truncated.getItems()
				.count()
		);
		assertFalse(
			truncated.hitCount()
				.isPresent()
		);
		// ...which is why the response is rejected rather than used and cached
		assertFalse(truncated.isWellFormed());
	}

	@Test
	public void aCompleteButEmptyResponseIsWellFormed() throws Exception {
		// hitCount:0 with an (empty) result collection is a legitimate answer
		EuropePMCListResult.Core empty = new EuropePMCListResult.Core(
				mapper.readTree(
					"{\"version\":\"6.9\",\"hitCount\":0,\"resultList\":{\"result\":[]}}"
				)
		);

		assertTrue(empty.isWellFormed());
	}

	@Test
	public void aReferenceListResponseIsWellFormed() throws Exception {
		EuropePMCListResult.Reference refs = new EuropePMCListResult.Reference(
				mapper.readTree(
					"{\"version\":\"6.9\",\"hitCount\":0,\"referenceList\":{\"reference\":[]}}"
				)
		);

		assertTrue(refs.isWellFormed());
	}

	@Test
	public void aBodyShorterThanItsDeclaredLengthIsDetected() {
		// truncation is invisible to the JSON parser, so the declared length is
		// checked before the body is parsed and cached
		assertTrue(CachingApiClient.isTruncated("100", null, 15));
		assertFalse(CachingApiClient.isTruncated("15", null, 15));
		assertFalse(CachingApiClient.isTruncated(null, null, 15));
		assertFalse(CachingApiClient.isTruncated("not-a-number", null, 15));
		// a decoded body legitimately differs in length from its encoded form
		assertFalse(CachingApiClient.isTruncated("100", "gzip", 15));
		assertTrue(CachingApiClient.isTruncated("100", "identity", 15));
	}

	@Test
	public void openCitationsExtractsDoiFromPidList() throws Exception {
		// Index v2 returns space-separated pid lists, not bare DOIs
		OpenCitationsCitation citation = new OpenCitationsCitation(
				mapper.readTree(
					"{\"oci\":\"061603886611-061301957054\","
							+ "\"citing\":\"omid:br/061603886611 doi:10.1016/j.jbi.2023.104436 openalex:W4384133663\","
							+ "\"cited\":\"omid:br/061301957054 doi:10.1136/bmjqs-2018-008370 openalex:W2910707576 pmid:30636200\","
							+ "\"creation\":\"2023-08\"}"
				)
		);

		assertEquals(
			"10.1016/j.jbi.2023.104436",
			citation.getCitingDoi()
				.orElse(null)
		);
		assertEquals(
			"10.1136/bmjqs-2018-008370",
			citation.getCitedDoi()
				.orElse(null)
		);
		assertTrue(citation.isComplete());
	}

	@Test
	public void openCitationsOmidOnlyLinkKeepsItsOmidAsAnIdentifier()
			throws Exception {
		// this used to be an incomplete link with no identifier and no title, so a
		// session dropped the citation entirely. The handle is a real identifier, we
		// simply do not model its scheme (IdType.UNK).
		OpenCitationsCitation citation = new OpenCitationsCitation(
				mapper.readTree(
					"{\"citing\":\"omid:br/061603886611 openalex:W4384133663\","
							+ "\"cited\":\"omid:br/061301957054\"}"
				)
		);

		assertFalse(
			citation.getCitingDoi()
				.isPresent()
		);
		assertFalse(
			citation.getCitedDoi()
				.isPresent()
		);
		assertTrue(citation.isComplete());
		assertEquals(
			"omid:br/061603886611",
			citation.getSource()
				.getIdentifier()
				.get()
				.getIdentifier()
				.orElse(null)
		);
	}
}
