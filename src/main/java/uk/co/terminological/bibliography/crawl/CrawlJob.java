package uk.co.terminological.bibliography.crawl;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import uk.co.terminological.bibliography.record.ImmutableRecordReference;

/**
 * One depth-restricted citation crawl, and everything needed to resume it.
 *
 * The job <em>is</em> the checkpoint. Every field here is persisted, so killing the process and starting it again
 * continues from the frontier rather than from the seeds — which is the difference between a crawl being a background
 * job and being a long tool call that cannot survive a restart.
 *
 * Mutable and not thread-safe by design: one worker owns a job at a time, and the only thing another thread does to it
 * is set {@link #cancel()}, which is a volatile flag read between records rather than a lock.
 */
public class CrawlJob {

	private final String id;
	private final Set<ImmutableRecordReference> seeds;
	private final Direction direction;
	private final int depth;
	private final int maxRecords;

	/** how deep each node was first reached; a node reached shallower is never re-expanded */
	private final Map<ImmutableRecordReference, Integer> depthOf = new LinkedHashMap<>();
	private final Deque<ImmutableRecordReference> frontier = new ArrayDeque<>();
	private final Set<ImmutableRecordReference> visited = new LinkedHashSet<>();
	private final Map<String, Integer> callsPerApi = new LinkedHashMap<>();

	private volatile JobStatus status = JobStatus.PENDING;
	private volatile boolean cancelled = false;
	private int errors = 0;
	private String stoppedBy = null;
	private Instant startedAt;
	private Instant finishedAt;

	public CrawlJob(
			String id, Set<ImmutableRecordReference> seeds, Direction direction,
			int depth, int maxRecords
	) {
		this.id = id;
		this.seeds = new LinkedHashSet<>(seeds);
		this.direction = direction;
		this.depth = depth;
		this.maxRecords = maxRecords;
		for (var seed : this.seeds) {
			this.depthOf.put(seed, 0);
			this.frontier.add(seed);
		}
	}

	public static CrawlJob of(
			Set<ImmutableRecordReference> seeds, Direction direction, int depth,
			int maxRecords
	) {
		return new CrawlJob(
				UUID.randomUUID()
					.toString(),
				seeds, direction, depth, maxRecords
		);
	}

	public String getId() { return this.id; }
	public Set<ImmutableRecordReference> getSeeds() { return this.seeds; }
	public Direction getDirection() { return this.direction; }
	public int getDepth() { return this.depth; }
	public int getMaxRecords() { return this.maxRecords; }
	public Set<ImmutableRecordReference> getVisited() { return this.visited; }
	public Deque<ImmutableRecordReference> getFrontier() { return this.frontier; }
	public Map<ImmutableRecordReference, Integer> getDepthOf() {
		return this.depthOf;
	}
	public Map<String, Integer> getCallsPerApi() { return this.callsPerApi; }
	public int getErrors() { return this.errors; }
	public JobStatus getStatus() { return this.status; }
	public Optional<String> getStoppedBy() {
		return Optional.ofNullable(this.stoppedBy);
	}
	public Optional<Instant> getStartedAt() {
		return Optional.ofNullable(this.startedAt);
	}
	public Optional<Instant> getFinishedAt() {
		return Optional.ofNullable(this.finishedAt);
	}

	/** Ask the worker to stop. Read between records, never mid-request: in-flight HTTP is left alone. */
	public void cancel() { this.cancelled = true; }

	public boolean isCancelled() { return this.cancelled; }

	public boolean isFinished() {
		return this.status == JobStatus.DONE || this.status == JobStatus.CANCELLED
				|| this.status == JobStatus.FAILED;
	}

	// ---- mutation, by the engine --------------------------------------------

	void setStatus(JobStatus status) { this.status = status; }

	void started() {
		this.status = JobStatus.RUNNING;
		if (this.startedAt == null) this.startedAt = Instant.now();
	}

	void finished(JobStatus status, String stoppedBy) {
		this.status = status;
		this.stoppedBy = stoppedBy;
		this.finishedAt = Instant.now();
	}

	void countError() { this.errors += 1; }

	void countCall(String api) {
		this.callsPerApi.merge(api, 1, Integer::sum);
	}

	/** Take the next node to expand, or empty when the frontier has drained. */
	Optional<ImmutableRecordReference> next() {
		return Optional.ofNullable(this.frontier.poll());
	}

	void markVisited(ImmutableRecordReference ref) { this.visited.add(ref); }

	/**
	 * Offer a newly discovered neighbour at the given depth.
	 *
	 * Refused when it is beyond the depth budget, or when it has already been reached — at any depth, because BFS
	 * reaches a node by its shortest path first and re-expanding it from a longer one would only repeat work.
	 */
	boolean discover(ImmutableRecordReference ref, int atDepth) {
		if (atDepth > this.depth) return false;
		if (this.depthOf.containsKey(ref)) return false;
		this.depthOf.put(ref, atDepth);
		this.frontier.add(ref);
		return true;
	}

	/**
	 * Restore a checkpoint.
	 *
	 * Public because the persistence layer lives in another package, and deliberately not a constructor: a job read
	 * back from the store is the same job, resumed, rather than a new one that happens to look like it.
	 */
	public void restore(
			Map<ImmutableRecordReference, Integer> depthOf,
			Iterable<ImmutableRecordReference> frontier,
			Iterable<ImmutableRecordReference> visited, JobStatus status, int errors,
			String stoppedBy, Instant startedAt, Instant finishedAt,
			Map<String, Integer> callsPerApi
	) {
		this.depthOf.clear();
		this.depthOf.putAll(depthOf);
		this.frontier.clear();
		frontier.forEach(this.frontier::add);
		this.visited.clear();
		visited.forEach(this.visited::add);
		this.callsPerApi.clear();
		this.callsPerApi.putAll(callsPerApi);
		this.status = status;
		this.errors = errors;
		this.stoppedBy = stoppedBy;
		this.startedAt = startedAt;
		this.finishedAt = finishedAt;
	}

	@Override
	public String toString() {
		return "CrawlJob[" + this.id + " " + this.status + " visited="
				+ this.visited.size() + " frontier=" + this.frontier.size() + "]";
	}

}
