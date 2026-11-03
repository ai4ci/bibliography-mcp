package uk.co.terminological.bibliography.api.opencitations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.TestPaperFixtures;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * OpenCitations asked by PMID, and answering with identifiers this project can use.
 *
 * The offline tests pin the rules; this checks they describe the live API. Both halves matter at once: a PMID-only
 * record used to get nothing (debt-002), and what came back was typed {@link IdType#UNK} unless it had a DOI
 * (issue-001).
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
@Category(LiveTest.class)
public class TestOpenCitationsIdentifiersLive {

	private static OpenCitationsClient client() {
		return OpenCitationsClient
			.create(java.nio.file.Paths.get("/tmp/ehcache/opencitations-ids-test"));
	}

	/** a paper OpenCitations actually indexes; the 2026 fixture is not in its corpus at all (0 references by DOI either) */
	private static final String INDEXED_PMID = "33687922";

	/** cites a preprint and a report, so some of its endpoints carry no DOI */
	private static final String NO_DOI_ENDPOINTS_DOI = "10.1016/S0140-6736(20)30260-9";

	@Test
	public void aPmidOnlyRecordGetsItsReferences() {
		var byPmid = client().citesReferences(
			List.of(Builder.recordReference(IdType.PMID, INDEXED_PMID))
		);

		System.out.println(
			"INFO: asked by PMID -> " + byPmid.size() + " references"
		);
		assertFalse(
			"a record known only by its PMID still got nothing", byPmid.isEmpty()
		);
	}

	@Test
	public void askingByPmidAndByDoiDescribeTheSameWork() {
		var byDoi = client().citesReferences(
			List.of(
				Builder.recordReference(
					IdType.DOI, TestPaperFixtures.CITATION_RICH_VOR_DOI
				)
			)
		);
		var byPmid = client().citesReferences(
			List.of(Builder.recordReference(IdType.PMID, "33687922"))
		);

		System.out.println(
			"INFO: by doi " + byDoi.size() + ", by pmid " + byPmid.size()
		);
		assertEquals(
			"the same paper by two names should give the same reference list",
			byDoi.size(), byPmid.size()
		);
	}

	@Test
	public void anEndpointWithNoDoiIsStillNamedWhenItCanBe() {
		var links = client().citesReferences(
			List.of(Builder.recordReference(IdType.DOI, NO_DOI_ENDPOINTS_DOI))
		);

		Map<IdType, Long> byType = links.stream()
			.map(CitationLink::getTarget)
			.map(t -> t.getIdentifier())
			.filter(java.util.Optional::isPresent)
			.map(java.util.Optional::get)
			.map(RecordReference::getIdentifierType)
			.collect(Collectors.groupingBy(t -> t, Collectors.counting()));
		System.out.println("INFO: cited endpoints by id type: " + byType);

		assertFalse("no citations came back at all", links.isEmpty());
		// measured: this paper cites two works OpenCitations knows by PMID and not
		// by DOI. They used to arrive as UNK and now arrive as PMIDs, which the
		// crawl and the dedup can do something with.
		assertTrue(
			"an endpoint known by PMID and not by DOI should be typed as a PMID: "
					+ byType,
			byType.getOrDefault(IdType.PMID, 0L) > 0
		);
		// and every endpoint carries an identifier of some kind, which is the part
		// of issue-001 that was about links being dropped entirely
		assertEquals(
			"every citation should have an identifiable endpoint",
			links.size(),
			byType.values()
				.stream()
				.mapToLong(Long::longValue)
				.sum()
		);
	}

}
