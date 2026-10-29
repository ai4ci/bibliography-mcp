package uk.co.terminological.bibliography.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.UnionFind;

/**
 * Tests for search deduplication and RRF ranking logic.
 * <p>
 * Tests the core identifier equivalence and scoring components in isolation.
 * The full {@code BiblioFacade.search()} flow additionally requires live API
 * clients and is covered by {@link TestSearchDedupLive}.
 */
public class TestSearchDedup {

	// == UnionFind ======================================================

	@Test
	public void singleElementHasItselfAsRoot() {
		var uf = new UnionFind<String>();
		uf.add("a");
		assertEquals(Optional.of("a"), uf.find("a"));
	}

	@Test
	public void unrelatedElementsStaySeparate() {
		var uf = new UnionFind<String>();
		uf.add("a");
		uf.add("b");
		assertEquals(Optional.of("a"), uf.find("a"));
		assertEquals(Optional.of("b"), uf.find("b"));
	}

	@Test
	public void unionMakesTwoElementsEquivalent() {
		var uf = new UnionFind<String>();
		uf.add("a");
		uf.add("b");
		uf.union("a", "b");
		assertEquals(uf.find("a"), uf.find("b"));
	}

	@Test
	public void transitiveUnionWorks() {
		var uf = new UnionFind<String>();
		uf.union("a", "b");
		uf.union("b", "c");
		assertEquals(uf.find("a"), uf.find("c"));
	}

	@Test
	public void unknownElementReturnsEmpty() {
		var uf = new UnionFind<String>();
		uf.add("a");
		assertEquals(Optional.empty(), uf.find("b"));
	}

	@Test
	public void groupsPartitionsCorrectly() {
		var uf = new UnionFind<String>();
		uf.union("doi:A", "pmid:1");
		uf.union("pmid:2", "pmcid:PMC2");
		uf.union("doi:A", "pmcid:PMC1"); // transitively: doi:A == pmid:1 == pmcid:PMC1
		var groups = uf.groups();
		assertEquals(2, groups.size());
		// first group: {doi:A, pmid:1, pmcid:PMC1}
		// second group: {pmid:2, pmcid:PMC2}
		Set<String> groupKeys = groups.keySet();
		assertTrue(
			"DOI group should exist",
			groupKeys.stream()
				.anyMatch(k -> k.startsWith("doi:"))
		);
	}

	// == RRF scoring ====================================================

	@Test
	public void rrfScoreInAllApis() {
		// Record returned 1st by all 3 APIs scores higher than 1st by 1 API
		var all3 = new ArrayList<BiblioFacade.ScoredHit>();
		all3.add(new BiblioFacade.ScoredHit(null, "crossref", 1));
		all3.add(new BiblioFacade.ScoredHit(null, "entrez", 1));
		all3.add(new BiblioFacade.ScoredHit(null, "europepmc", 1));
		double scoreAll3 = BiblioFacade.rrfScore(all3, 5);

		var single = new ArrayList<BiblioFacade.ScoredHit>();
		single.add(new BiblioFacade.ScoredHit(null, "crossref", 1));
		double score1 = BiblioFacade.rrfScore(single, 5);

		assertTrue(
			"Record in all APIs should score higher than record in 1 API",
			scoreAll3 > score1
		);
	}

	@Test
	public void rrfScoreHighRankWins() {
		// Same record in 2 APIs: rank 1+10 scores higher than rank 10+10
		var high = new ArrayList<BiblioFacade.ScoredHit>();
		high.add(new BiblioFacade.ScoredHit(null, "crossref", 1));
		high.add(new BiblioFacade.ScoredHit(null, "entrez", 10));
		double highScore = BiblioFacade.rrfScore(high, 5);

		var low = new ArrayList<BiblioFacade.ScoredHit>();
		low.add(new BiblioFacade.ScoredHit(null, "crossref", 10));
		low.add(new BiblioFacade.ScoredHit(null, "entrez", 10));
		double lowScore = BiblioFacade.rrfScore(low, 5);

		assertTrue("Higher ranks should produce higher RRF scores", highScore > lowScore);
	}

	@Test
	public void rrfScoreIsZeroForEmptyHits() {
		assertEquals("Empty hit list should score 0",
			0.0, BiblioFacade.rrfScore(List.of(), 5), 1e-9);
	}

	@Test
	public void rrfScoreFirstRankGivesExpectedValue() {
		// 1/(5+1) = 1/6 ≈ 0.1667
		var hits = new ArrayList<BiblioFacade.ScoredHit>();
		hits.add(new BiblioFacade.ScoredHit(null, "crossref", 1));
		double score = BiblioFacade.rrfScore(hits, 5);
		assertEquals("RRF score should be 1/(K+rank)", 1.0 / 6.0, score, 1e-9);
	}

	// == ImmutableRecordReference =======================================

	@Test
	public void immutablesRefBuildsFromIdTypeAndString() {
		var ref = ImmutableRecordReference.builder()
			.identifierType(IdType.DOI)
			.identifier("10.1234/test")
			.build();
		assertEquals(IdType.DOI, ref.getIdentifierType());
		assertEquals(Optional.of("10.1234/test"), ref.getIdentifier());
	}
}
