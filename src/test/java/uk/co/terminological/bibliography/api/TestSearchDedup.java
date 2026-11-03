package uk.co.terminological.bibliography.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordReferenceMapping;
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

	// == the dedup itself ===============================================
	//
	// plan-008's point: two APIs describing one paper under two identifier schemes
	// should come back as one record. These drive dedupedSearch directly, with the
	// identifier mappings supplied rather than fetched, so what is asserted is the
	// grouping and not the network.

	/** a facade whose id mapping is whatever the test says it is */
	private static class MappedFacade extends BiblioFacade {
		private final Set<RecordReferenceMapping> canned;

		MappedFacade(Set<RecordReferenceMapping> canned) throws IOException {
			super(noSecrets());
			this.canned = canned;
		}

		@Override
		public Set<RecordReferenceMapping> mappings(
				Collection<? extends RecordReference> ids
		) {
			return this.canned;
		}
	}

	private static java.nio.file.Path noSecrets() throws IOException {
		var empty = java.nio.file.Files.createTempFile("biblio-no-secrets", ".prop");
		empty.toFile()
			.deleteOnExit();
		return empty;
	}

	private static ImmutableRecordReference ref(IdType type, String id) {
		return ImmutableRecordReference.builder()
			.identifierType(type)
			.identifier(id)
			.build();
	}

	private static RecordReferenceMapping mapping(
			IdType fromType, String from, IdType toType, String to
	) {
		return Builder.recordReferenceMapping(
			ref(fromType, from), ref(toType, to)
		);
	}

	private static List<BiblioFacade.ScoredHit> hits(Object... apiRankRecord) {
		var out = new ArrayList<BiblioFacade.ScoredHit>();
		for (int i = 0; i < apiRankRecord.length; i += 3) {
			out.add(
				new BiblioFacade.ScoredHit(
						(Record) apiRankRecord[i + 2],
						(String) apiRankRecord[i],
						(Integer) apiRankRecord[i + 1]
				)
			);
		}
		return out;
	}

	@Test
	public void identicalDoiMerged() throws Exception {
		var facade = new MappedFacade(Set.of());
		var fromCrossref = new StubRecord(IdType.DOI, "10.1/x", "A title", "crossref");
		var fromEntrez = new StubRecord(IdType.DOI, "10.1/x", "A TITLE", "entrez");

		var out = facade.dedupedSearch(
			hits("crossref", 1, fromCrossref, "entrez", 1, fromEntrez)
		);

		assertEquals("one paper, found twice, is one record", 1, out.size());
	}

	@Test
	public void doiPmidEquivalence() throws Exception {
		// neither record knows the other's identifier; only the mapping joins them,
		// which is the case CrossRef creates by returning no other identifiers
		var facade = new MappedFacade(
				Set.of(mapping(IdType.DOI, "10.1/x", IdType.PMID, "12345"))
		);
		var byDoi = new StubRecord(IdType.DOI, "10.1/x", "A title", "crossref");
		var byPmid = new StubRecord(IdType.PMID, "12345", "A title", "entrez");

		var out = facade.dedupedSearch(
			hits("crossref", 1, byDoi, "entrez", 1, byPmid)
		);

		assertEquals(1, out.size());
	}

	@Test
	public void threeWayMerge() throws Exception {
		var facade = new MappedFacade(
				Set.of(
					mapping(IdType.DOI, "10.1/x", IdType.PMID, "12345"),
					mapping(IdType.PMID, "12345", IdType.PMCID, "PMC9")
				)
		);
		var byDoi = new StubRecord(IdType.DOI, "10.1/x", "A title", "crossref");
		var byPmid = new StubRecord(IdType.PMID, "12345", "A title", "entrez");
		var byPmcid = new StubRecord(IdType.PMCID, "PMC9", "A title", "europepmc");

		var out = facade.dedupedSearch(
			hits(
				"crossref", 1, byDoi, "entrez", 1, byPmid, "europepmc", 1, byPmcid
			)
		);

		assertEquals("the chain joins all three", 1, out.size());
	}

	@Test
	public void noMappingKeepsSeparate() throws Exception {
		var facade = new MappedFacade(
				Set.of(mapping(IdType.DOI, "10.1/other", IdType.PMID, "999"))
		);
		var one = new StubRecord(IdType.DOI, "10.1/x", "A title", "crossref");
		var two = new StubRecord(IdType.PMID, "12345", "Another title", "entrez");

		var out = facade.dedupedSearch(hits("crossref", 1, one, "entrez", 1, two));

		assertEquals("a mapping between other papers joins nothing", 2, out.size());
	}

	@Test
	public void mappingWithNoResults() throws Exception {
		var facade = new MappedFacade(Set.of());
		var one = new StubRecord(IdType.DOI, "10.1/x", "A title", "crossref");
		var two = new StubRecord(IdType.PMID, "12345", "Another title", "entrez");

		var out = facade.dedupedSearch(hits("crossref", 1, one, "entrez", 1, two));

		assertEquals("no mappings means no merging", 2, out.size());
	}

	@Test
	public void corroboratedResultsRankAboveSingleApiOnes() throws Exception {
		// what the ranking is for: a paper two APIs agree on beats one that only
		// one API found, even where the latter was that API's top hit
		var facade = new MappedFacade(
				Set.of(mapping(IdType.DOI, "10.1/both", IdType.PMID, "12345"))
		);
		var bothByDoi = new StubRecord(
				IdType.DOI, "10.1/both", "Corroborated", "crossref"
		);
		var bothByPmid = new StubRecord(
				IdType.PMID, "12345", "Corroborated", "entrez"
		);
		var onlyCrossref = new StubRecord(
				IdType.DOI, "10.1/alone", "Uncorroborated", "crossref"
		);

		var out = new ArrayList<>(
				facade.dedupedSearch(
					hits(
						"crossref", 2, bothByDoi,
						"crossref", 1, onlyCrossref,
						"entrez", 5, bothByPmid
					)
				)
		);

		assertEquals(2, out.size());
		assertEquals(
			"two APIs at ranks 2 and 5 beat one API at rank 1",
			Optional.of("Corroborated"),
			out.get(0)
				.getTitle()
		);
	}

	@Test
	public void theScoreThatOrderedTheResultsIsReported() throws Exception {
		// plan-012: the RRF score was computed, used to sort, and then dropped at the
		// return, so an agent could not see why an order was chosen or how
		// corroborated a hit was
		var facade = new MappedFacade(
				Set.of(mapping(IdType.DOI, "10.1/both", IdType.PMID, "12345"))
		);
		var bothByDoi = new StubRecord(
				IdType.DOI, "10.1/both", "Corroborated", "crossref"
		);
		var bothByPmid = new StubRecord(
				IdType.PMID, "12345", "Corroborated", "entrez"
		);
		var alone = new StubRecord(
				IdType.DOI, "10.1/alone", "Uncorroborated", "crossref"
		);

		var scored = facade.scoredSearch(
			hits(
				"crossref", 2, bothByDoi, "crossref", 1, alone, "entrez", 5,
				bothByPmid
			)
		);

		assertEquals(2, scored.size());
		var top = scored.get(0);
		assertEquals(
			Optional.of("Corroborated"),
			top.record()
				.getTitle()
		);
		assertTrue("a score should be reported", top.score() > 0);
		assertEquals(
			"the APIs that found it are the legible form of corroboration",
			List.of("crossref", "entrez"),
			top.matchedApis()
		);
		assertTrue(
			"corroboration should outrank a single API's top hit",
			top.score() > scored.get(1)
				.score()
		);
		assertEquals(
			List.of("crossref"),
			scored.get(1)
				.matchedApis()
		);
	}

	/** the least a Record can be: an identity, a title and where it came from */
	static class StubRecord implements Record {
		private final IdType idType;
		private final String id;
		private final String title;
		private final Provenance provenance;

		StubRecord(IdType idType, String id, String title, String source) {
			this.idType = idType;
			this.id = id;
			this.title = title;
			this.provenance = Provenance.placeholder(source);
		}

		@Override
		public Optional<String> getIdentifier() { return Optional.of(this.id); }
		@Override
		public IdType getIdentifierType() { return this.idType; }
		@Override
		public List<RecordReference> getOtherIdentifiers() { return List.of(); }
		@Override
		public List<Author> getAuthors() { return List.of(); }
		@Override
		public java.util.stream.Stream<String> getLicenses() {
			return java.util.stream.Stream.empty();
		}
		@Override
		public Optional<String> getAbstract() { return Optional.empty(); }
		@Override
		public Optional<String> getTitle() { return Optional.of(this.title); }
		@Override
		public Optional<String> getJournal() { return Optional.empty(); }
		@Override
		public Optional<java.time.LocalDate> getDate() { return Optional.empty(); }
		@Override
		public Optional<java.net.URI> getPdfUri() { return Optional.empty(); }
		@Override
		public Provenance getProvenance() { return this.provenance; }
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
