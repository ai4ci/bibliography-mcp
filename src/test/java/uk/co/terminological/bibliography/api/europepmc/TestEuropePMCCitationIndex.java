package uk.co.terminological.bibliography.api.europepmc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * What index a Europe PMC citation link carries (issue-026).
 *
 * {@code CitationLink.getIndex()} is the position in the citing work's reference list, and is only meaningful for a
 * link read from such a list: a "cited by" answer has no list to be a position in. Europe PMC honoured neither half —
 * a counter was declared and never incremented, so a whole reference list was reported as position zero, and the same
 * counter was passed in the "cited by" direction where there should be no index at all.
 *
 * These are facts about how a list becomes links, so they are pinned without a call; the live agreement between
 * identifiers is {@code TestEuropePMCClientEndToEnd}'s job.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestEuropePMCCitationIndex {

	private static final RecordReference CITING = Builder
		.recordReference(IdType.PMID, "33577623");

	private static List<RecordReference> three() {
		return List.of(
			Builder.recordReference(IdType.DOI, "10.1000/aaa"),
			Builder.recordReference(IdType.DOI, "10.1000/bbb"),
			Builder.recordReference(IdType.DOI, "10.1000/ccc")
		);
	}

	@Test
	public void aReferenceListIsNumberedFromOne() {
		var links = EuropePMCClient.referenceList(CITING, three());

		assertEquals(3, links.size());
		assertEquals(
			"a reference list is positions 1..n, not n copies of zero",
			List.of(1, 2, 3),
			links.stream()
				.map(
					l -> l.getIndex()
						.orElse(null)
				)
				.sorted()
				.collect(Collectors.toList())
		);
	}

	@Test
	public void everyReferenceKeepsItsOwnPlace() {
		var links = EuropePMCClient.referenceList(CITING, three());

		// the links are collected into a set, and CitationLink is an Immutables value
		// type whose index is part of its equals — so a list whose entries differ only
		// by position collapses to one entry when every index is the same
		assertEquals(
			"three distinct positions must be three distinct links", 3,
			links.stream()
				.distinct()
				.count()
		);
	}

	@Test
	public void aCitedByLinkHasNoIndex() {
		var citing = three();
		var links = EuropePMCClient.citedByList(CITING, citing);

		assertEquals(3, links.size());
		for (CitationLink link : links) {
			assertFalse(
				"a 'cited by' answer is not a reference list, so there is no position"
						+ " in one to report",
				link.getIndex()
					.isPresent()
			);
		}
	}

	@Test
	public void aCitedByLinkRunsFromTheCitingWork() {
		// the direction is the contract issue-007 settled: source cites target, so the
		// work asked about is the target of everything a 'cited by' answer returns
		var links = EuropePMCClient.citedByList(CITING, three());

		assertTrue(
			links.stream()
				.allMatch(
					l -> l.getTarget()
						.getIdentifier()
						.equals(
							java.util.Optional.of(
								Builder.citationReference(CITING)
									.getIdentifier()
									.get()
							)
						)
				)
		);
	}

}
