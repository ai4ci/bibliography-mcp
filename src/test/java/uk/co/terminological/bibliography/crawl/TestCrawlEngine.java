package uk.co.terminological.bibliography.crawl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.repository.RepositoryStore;
import uk.co.terminological.bibliography.repository.SqliteRepositoryStore;

/**
 * plan-011: a depth-restricted citation crawl that runs in the background, survives a restart and reports itself.
 *
 * Offline throughout, over a citation graph declared in the test rather than fetched. What is asserted is the
 * traversal — depth budgets, no re-expansion, ceilings, errors that do not kill a crawl, and a checkpoint that
 * resumes — because those are the properties that decide whether a crawl of 25 seeds to depth 2 is a background job
 * or an expensive accident.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestCrawlEngine {

	private Path dir;
	private RepositoryStore store;
	private FakeSource source;
	private CrawlEngine engine;

	@Before
	public void open() throws Exception {
		this.dir = Files.createTempDirectory("biblio-crawl-test");
		this.store = SqliteRepositoryStore.open(this.dir.resolve("repo.db"));
		this.source = new FakeSource();
		this.engine = new CrawlEngine(this.store, this.source);
	}

	@After
	public void close() {
		if (this.engine != null) this.engine.close();
		if (this.store != null) this.store.close();
	}

	private static ImmutableRecordReference ref(String id) {
		return Builder.recordReference(IdType.DOI, id);
	}

	private static Set<ImmutableRecordReference> seeds(String... ids) {
		Set<ImmutableRecordReference> out = new LinkedHashSet<>();
		for (String id : ids)
			out.add(ref(id));
		return out;
	}

	private Set<String> visitedIds(CrawlJob job) {
		return job.getVisited()
			.stream()
			.map(
				r -> r.getIdentifier()
					.orElse("")
			)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	// ---- depth ----

	@Test
	public void depthOneVisitsTheSeedsAndTheirNeighboursAndNoFurther() {
		this.source.cites("a", "b", "c");
		this.source.cites("b", "d");

		var job = this.engine.runSynchronously(
			CrawlJob.of(seeds("a"), Direction.DOWN, 1, 1000)
		);

		assertEquals(JobStatus.DONE, job.getStatus());
		// a is expanded; b and c are reached at depth 1 and not expanded, so d is
		// never seen
		assertEquals(Set.of("a", "b", "c"), visitedIds(job));
	}

	@Test
	public void depthTwoReachesNeighboursOfNeighbours() {
		this.source.cites("a", "b", "c");
		this.source.cites("b", "d");

		var job = this.engine.runSynchronously(
			CrawlJob.of(seeds("a"), Direction.DOWN, 2, 1000)
		);

		assertEquals(Set.of("a", "b", "c", "d"), visitedIds(job));
	}

	@Test
	public void aNodeReachedShallowerIsNotExpandedAgain() {
		// a -> b, a -> c, c -> b: b is reached at depth 1 by two routes
		this.source.cites("a", "b", "c");
		this.source.cites("c", "b");
		this.source.cites("b", "d");

		var job = this.engine.runSynchronously(
			CrawlJob.of(seeds("a"), Direction.DOWN, 2, 1000)
		);

		assertEquals(
			"b should have been expanded once, from its shallowest reach",
			1,
			this.source.expansionsOf("b")
		);
		assertTrue(visitedIds(job).contains("d"));
	}

	@Test
	public void bothDirectionsCountAsOneHop() {
		this.source.cites("a", "down1");
		this.source.citedBy("a", "up1");
		this.source.cites("down1", "down2");
		this.source.citedBy("up1", "up2");

		var job = this.engine.runSynchronously(
			CrawlJob.of(seeds("a"), Direction.BOTH, 1, 1000)
		);

		// one hop in both directions, not one hop each way then another
		assertEquals(Set.of("a", "down1", "up1"), visitedIds(job));
	}

	// ---- budgets, errors, cancellation ----

	@Test
	public void theRecordCeilingStopsTheJobAndSaysWhy() {
		this.source.cites("a", "b", "c", "d", "e", "f");

		var job = this.engine.runSynchronously(
			CrawlJob.of(seeds("a"), Direction.DOWN, 2, 3)
		);

		assertEquals(JobStatus.DONE, job.getStatus());
		assertEquals(Optional.of("recordCeiling"), job.getStoppedBy());
		assertTrue(
			"the ceiling should bound what was visited: " + visitedIds(job),
			job.getVisited()
				.size() <= 3
		);
	}

	@Test
	public void oneFailingIdIsCountedAndTheCrawlCarriesOn() {
		this.source.cites("a", "b", "bad", "c");
		this.source.throwsOn("bad");

		var job = this.engine.runSynchronously(
			CrawlJob.of(seeds("a"), Direction.DOWN, 2, 1000)
		);

		assertEquals(JobStatus.DONE, job.getStatus());
		assertEquals("the bad id should be counted once", 1, job.getErrors());
		assertTrue(
			"the rest of the frontier should still have been crawled",
			visitedIds(job).containsAll(List.of("a", "b", "c"))
		);
	}

	@Test
	public void cancellingHaltsWithinOneRecord() {
		for (int i = 0; i < 50; i++)
			this.source.cites("a", "n" + i);
		var job = CrawlJob.of(seeds("a"), Direction.DOWN, 3, 10000);
		this.source.onExpand(ref -> {
			// cancel as soon as the crawl moves past the seed
			if (!"a".equals(
				ref.getIdentifier()
					.orElse("")
			)) job.cancel();
		});

		this.engine.runSynchronously(job);

		assertEquals(JobStatus.CANCELLED, job.getStatus());
		assertTrue(
			"a cancelled crawl should stop near where it was asked to: "
					+ job.getVisited()
						.size(),
			job.getVisited()
				.size() < 10
		);
	}

	@Test
	public void aSecondCrawlIsRefusedWhileOneIsRunning() throws Exception {
		this.source.cites("a", "b");
		var running = this.engine.start(seeds("a"), Direction.DOWN, 1, 10);

		try {
			this.engine.start(seeds("z"), Direction.DOWN, 1, 10);
			fail("a second crawl should be refused while one is running");
		} catch (IllegalStateException e) {
			assertTrue(
				e.getMessage(),
				e.getMessage()
					.contains(running.getId())
			);
		} finally {
			this.engine.stop(running.getId());
		}
	}

	// ---- what reaches the repository ----

	@Test
	public void edgesAndRecordsReachTheRepository() {
		this.source.cites("a", "b");

		this.engine.runSynchronously(
			CrawlJob.of(seeds("a"), Direction.DOWN, 1, 100)
		);

		assertEquals(
			"the edge a->b should have been stored", 1,
			this.store.citationCount()
		);
		assertTrue(
			"the records the crawl fetched should have been stored",
			this.store.recordCount() >= 2
		);
		assertEquals(
			Optional.of("Record a"),
			this.store.getRecord(ref("a"))
				.flatMap(Record::getTitle)
		);
	}

	// ---- durability ----

	@Test
	public void aJobResumesFromItsFrontierAfterARestart() throws Exception {
		this.source.cites("a", "b", "c");
		this.source.cites("b", "d");
		this.source.cites("c", "e");

		// stop the crawl once it has expanded the seed, as a kill mid-crawl would
		var job = CrawlJob.of(seeds("a"), Direction.DOWN, 2, 1000);
		this.source.onExpand(ref -> {
			if (!"a".equals(
				ref.getIdentifier()
					.orElse("")
			)) job.cancel();
		});
		this.engine.runSynchronously(job);
		var frontierBefore = new ArrayList<>(job.getFrontier());
		var visitedBefore = new LinkedHashSet<>(job.getVisited());
		assertFalse("the crawl should have been interrupted mid-frontier",
			frontierBefore.isEmpty());

		this.engine.close();
		this.store.close();

		// a new process over the same repository
		try (var reopened = SqliteRepositoryStore
			.open(this.dir.resolve("repo.db"))) {
			var restored = reopened.job(job.getId());
			assertTrue("the job did not survive the restart", restored.isPresent());
			assertEquals(visitedBefore, restored.get()
				.getVisited());
			assertEquals(
				frontierBefore, new ArrayList<>(restored.get()
					.getFrontier())
			);

			// and it continues rather than starting over
			this.source.onExpand(ref -> {});
			var resumedEngine = new CrawlEngine(reopened, this.source);
			var finished = resumedEngine.runSynchronously(restored.get());
			resumedEngine.close();
			assertEquals(JobStatus.DONE, finished.getStatus());
			assertTrue(
				"resuming should reach what the first run had not: "
						+ visitedIds(finished),
				visitedIds(finished).containsAll(List.of("a", "b", "c"))
			);
		}
		this.store = null;
		this.engine = null;
	}

	// ---- a citation graph declared rather than fetched ----

	static class FakeSource implements CrawlSource {

		private final Map<String, List<String>> references = new LinkedHashMap<>();
		private final Map<String, List<String>> citedBy = new LinkedHashMap<>();
		private final Map<String, Integer> expansions = new LinkedHashMap<>();
		private final Set<String> failing = new LinkedHashSet<>();
		private java.util.function.Consumer<RecordReference> onExpand = r -> {};

		void cites(String from, String... to) {
			this.references.computeIfAbsent(from, k -> new ArrayList<>())
				.addAll(List.of(to));
		}

		void citedBy(String target, String... citing) {
			this.citedBy.computeIfAbsent(target, k -> new ArrayList<>())
				.addAll(List.of(citing));
		}

		void throwsOn(String id) { this.failing.add(id); }

		void onExpand(java.util.function.Consumer<RecordReference> hook) {
			this.onExpand = hook;
		}

		int expansionsOf(String id) {
			return this.expansions.getOrDefault(id, 0);
		}

		private String idOf(RecordReference ref) {
			return ref.getIdentifier()
				.orElse("");
		}

		@Override
		public Collection<? extends CitationLink> citesReferences(
				Collection<? extends RecordReference> refs
		) {
			return links(refs, this.references, true);
		}

		@Override
		public Collection<? extends CitationLink> referencesCiting(
				Collection<? extends RecordReference> refs
		) {
			return links(refs, this.citedBy, false);
		}

		private Collection<? extends CitationLink> links(
				Collection<? extends RecordReference> refs,
				Map<String, List<String>> graph, boolean outbound
		) {
			List<CitationLink> out = new ArrayList<>();
			for (RecordReference ref : refs) {
				var id = idOf(ref);
				this.onExpand.accept(ref);
				this.expansions.merge(id, 1, Integer::sum);
				if (this.failing.contains(id))
					throw new IllegalStateException("the API refused " + id);
				for (String other : graph.getOrDefault(id, List.of())) {
					var otherRef = Builder.recordReference(IdType.DOI, other);
					out.add(
						outbound ? Builder.citationLink(ref, otherRef, null)
							: Builder.citationLink(otherRef, ref, null)
					);
				}
			}
			return out;
		}

		@Override
		public Map<ImmutableRecordReference, ? extends Record> fetch(
				Collection<? extends RecordReference> ids
		) {
			Map<ImmutableRecordReference, Record> out = new LinkedHashMap<>();
			for (RecordReference ref : ids) {
				out.put(ref.asId(), new FakeRecord(ref.asId()));
			}
			return out;
		}
	}

	static class FakeRecord implements Record {
		private final ImmutableRecordReference ref;

		FakeRecord(ImmutableRecordReference ref) { this.ref = ref; }

		@Override
		public Optional<String> getIdentifier() {
			return this.ref.getIdentifier();
		}
		@Override
		public IdType getIdentifierType() { return this.ref.getIdentifierType(); }
		@Override
		public List<RecordReference> getOtherIdentifiers() { return List.of(); }
		@Override
		public List<uk.co.terminological.bibliography.record.Author> getAuthors() {
			return List.of();
		}
		@Override
		public java.util.stream.Stream<String> getLicenses() {
			return java.util.stream.Stream.empty();
		}
		@Override
		public Optional<String> getAbstract() { return Optional.empty(); }
		@Override
		public Optional<String> getTitle() {
			return Optional.of(
				"Record " + this.ref.getIdentifier()
					.orElse("?")
			);
		}
		@Override
		public Optional<String> getJournal() { return Optional.empty(); }
		@Override
		public Optional<java.time.LocalDate> getDate() { return Optional.empty(); }
		@Override
		public Optional<java.net.URI> getPdfUri() { return Optional.empty(); }
		@Override
		public Provenance getProvenance() {
			return Provenance.of("fake", "journal-article");
		}
	}

}
