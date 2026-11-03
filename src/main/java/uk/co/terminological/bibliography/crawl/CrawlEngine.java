package uk.co.terminological.bibliography.crawl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.repository.RepositoryStore;

/**
 * A breadth-first citation crawl, bounded by depth and by a record ceiling, run on a thread of its own.
 *
 * Breadth-first because the budget is depth: a node is reached by its shortest path first, so a node already seen is
 * never expanded again, and a crawl stopped early has the shallow — and most relevant — part of the graph rather than
 * one deep arbitrary branch of it.
 *
 * <h2>What it writes as it goes</h2>
 *
 * Every edge and every record it fetches goes into the {@link RepositoryStore} immediately, and the job itself is
 * checkpointed every {@value #CHECKPOINT_EVERY} records. That is what makes the crawl survivable: killing the process
 * loses at most the last few records, and the job resumes from its frontier rather than from its seeds.
 *
 * <h2>One job at a time</h2>
 *
 * Deliberately. A second {@link #start} while one is running is refused and names the running job, because two crawls
 * on one repository would race on the same records and spend two API budgets at once. A queue can come later; this is
 * the thing that has to be right first.
 */
// [IMPLEMENTS](/design/features/feat-010-crawl-engine-with-zotero-mirror.md)
public class CrawlEngine implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(CrawlEngine.class);

	/** How often the job is written to the store, in records expanded. */
	public static final int CHECKPOINT_EVERY = 50;

	/** How many identifiers are fetched in one call; the clients batch internally too. */
	private static final int FETCH_BATCH = 100;

	private final RepositoryStore store;
	private final CrawlSource source;
	private final ExecutorService worker;
	private final AtomicReference<CrawlJob> current = new AtomicReference<>();
	private final AtomicReference<java.util.function.Consumer<CrawlJob>> progress = new AtomicReference<>();

	public CrawlEngine(RepositoryStore store, CrawlSource source) {
		this.store = store;
		this.source = source;
		this.worker = Executors.newSingleThreadExecutor(r -> {
			var t = new Thread(r, "crawl-engine");
			// a crawl must not keep the server alive on its own
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Be told as the crawl advances, at each checkpoint and when it ends.
	 *
	 * One listener, replaced rather than added to, because there is one crawl at a time and one client that started
	 * it. A crawl is hours of API budget, so a listener that throws is logged and ignored: a client that disconnected
	 * must not be able to stop it.
	 */
	public void onProgress(java.util.function.Consumer<CrawlJob> listener) {
		this.progress.set(listener);
	}

	private void report(CrawlJob job) {
		var listener = this.progress.get();
		if (listener == null) return;
		try {
			listener.accept(job);
		} catch (RuntimeException e) {
			log.debug(
				"a crawl progress listener threw, and the crawl carries on: {}",
				e.getMessage()
			);
		}
	}

	/**
	 * Start a crawl and return immediately with the job.
	 *
	 * @throws IllegalStateException when a crawl is already running, naming it
	 */
	public CrawlJob start(
			Set<ImmutableRecordReference> seeds, Direction direction, int depth,
			int maxRecords
	) {
		var job = CrawlJob.of(seeds, direction, depth, maxRecords);
		var running = this.current.get();
		if (running != null && !running.isFinished())
			throw new IllegalStateException(
					"a crawl is already running: " + running.getId()
							+ ". Stop it before starting another."
			);
		this.current.set(job);
		this.store.putJob(job);
		this.worker.submit(() -> {
			try {
				runSynchronously(job);
			} catch (RuntimeException e) {
				log.error("crawl {} failed", job.getId(), e);
				job.finished(JobStatus.FAILED, e.getMessage());
				this.store.putJob(job);
			}
		});
		return job;
	}

	/** The job as it stands, from memory when it is the running one and from the store otherwise. */
	public Optional<CrawlJob> status(String id) {
		var running = this.current.get();
		if (running != null && running.getId()
			.equals(id)) return Optional.of(running);
		return this.store.job(id);
	}

	/** Ask a crawl to stop. It halts between records, never mid-request. */
	public boolean stop(String id) {
		var running = this.current.get();
		if (running == null || !running.getId()
			.equals(id)) return false;
		running.cancel();
		return true;
	}

	/**
	 * Run a job to completion on the calling thread.
	 *
	 * The body of the crawl, and what the executor calls. Visible so that tests drive a crawl without a thread
	 * boundary in the way of the assertions.
	 */
	public CrawlJob runSynchronously(CrawlJob job) {
		job.started();
		this.store.putJob(job);
		int sinceCheckpoint = 0;
		try {
			while (true) {
				if (job.isCancelled()) {
					job.finished(JobStatus.CANCELLED, "cancelled");
					break;
				}
				if (job.getVisited()
					.size() >= job.getMaxRecords()) {
					job.finished(JobStatus.DONE, "recordCeiling");
					break;
				}
				var next = job.next();
				if (next.isEmpty()) {
					job.finished(JobStatus.DONE, "frontierDrained");
					break;
				}
				expand(job, next.get());
				if (++sinceCheckpoint >= CHECKPOINT_EVERY) {
					this.store.putJob(job);
					sinceCheckpoint = 0;
					report(job);
				}
			}
		} finally {
			this.store.putJob(job);
			// always the last word, so a listener that hears nothing else still
			// learns the crawl ended and why
			report(job);
		}
		log.info(
			"crawl {} {}: visited {}, frontier {}, errors {}",
			job.getId(),
			job.getStatus(),
			job.getVisited()
				.size(),
			job.getFrontier()
				.size(),
			job.getErrors()
		);
		return job;
	}

	/**
	 * Expand one node: fetch what it is, follow its edges, and offer the far ends to the frontier.
	 *
	 * A node at the depth limit is still visited and still fetched — the caller asked for it — but its edges are not
	 * followed, because following them is what depth bounds.
	 */
	private void expand(CrawlJob job, ImmutableRecordReference ref) {
		job.markVisited(ref);
		int depth = job.getDepthOf()
			.getOrDefault(ref, 0);
		store(ref);
		if (depth >= job.getDepth()) return;

		List<CitationLink> edges = new ArrayList<>();
		if (job.getDirection()
			.follows(Direction.DOWN))
			edges.addAll(edgesFrom(job, ref, true));
		if (job.getDirection()
			.follows(Direction.UP))
			edges.addAll(edgesFrom(job, ref, false));

		Set<ImmutableRecordReference> discovered = new LinkedHashSet<>();
		for (CitationLink edge : edges) {
			this.store.putCitation(edge);
			// the far end of the edge, whichever end the work asked about was
			endpoint(edge, ref).ifPresent(far -> {
				if (job.discover(far, depth + 1)) discovered.add(far);
			});
		}
		storeAll(discovered);
	}

	private Collection<? extends CitationLink> edgesFrom(
			CrawlJob job, ImmutableRecordReference ref, boolean down
	) {
		try {
			job.countCall(down ? "cites" : "citedBy");
			return down ? this.source.citesReferences(List.of(ref))
				: this.source.referencesCiting(List.of(ref));
		} catch (RuntimeException e) {
			// one bad identifier must never kill a crawl; note it and carry on
			log.warn(
				"crawl {}: {} failed for {}: {}",
				job.getId(),
				down ? "cites" : "citedBy",
				ref.getIdentifier()
					.orElse("?"),
				e.getMessage()
			);
			job.countError();
			return List.of();
		}
	}

	/** The end of the edge that is not the work being expanded. */
	private static Optional<ImmutableRecordReference> endpoint(
			CitationLink edge, ImmutableRecordReference asked
	) {
		var source = edge.getSource()
			.getIdentifier()
			.map(RecordReference::asId);
		var target = edge.getTarget()
			.getIdentifier()
			.map(RecordReference::asId);
		if (source.isPresent() && source.get()
			.equals(asked)) return target;
		if (target.isPresent() && target.get()
			.equals(asked)) return source;
		// an edge that names neither end as the work asked about: keep the target,
		// which is the one a reference list is about
		return target.isPresent() ? target : source;
	}

	private void store(ImmutableRecordReference ref) {
		storeAll(Set.of(ref));
	}

	/** Fetch and store records in batches; a failure here is counted and does not stop the crawl. */
	private void storeAll(Set<ImmutableRecordReference> refs) {
		if (refs.isEmpty()) return;
		List<ImmutableRecordReference> batch = new ArrayList<>(refs);
		for (int i = 0; i < batch.size(); i += FETCH_BATCH) {
			var chunk = batch.subList(
				i, Math.min(batch.size(), i + FETCH_BATCH)
			);
			try {
				this.source.fetch(chunk)
					.values()
					.forEach(this.store::putRecord);
			} catch (RuntimeException e) {
				log.warn("crawl: fetch failed for {} ids: {}", chunk.size(), e.getMessage());
			}
		}
	}

	@Override
	public void close() {
		var running = this.current.get();
		if (running != null && !running.isFinished()) running.cancel();
		this.worker.shutdown();
		try {
			if (!this.worker.awaitTermination(5, TimeUnit.SECONDS))
				this.worker.shutdownNow();
		} catch (InterruptedException e) {
			this.worker.shutdownNow();
			Thread.currentThread()
				.interrupt();
		}
	}

}
