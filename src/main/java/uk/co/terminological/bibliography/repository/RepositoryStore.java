package uk.co.terminological.bibliography.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * The repository of record: durable, versioned, and queryable without loading it.
 *
 * This replaces a {@code Session} serialised with {@code ObjectOutputStream} (debt-001), and the three properties it
 * adds are the ones a background crawl needs. It survives the process, so a crawl can outlive the request that started
 * it. It can be appended to continuously, so a crash loses the last write rather than the repository. And it answers
 * "the top hundred by score" without deserialising everything, so ranking a crawl of 10<sup>5</sup> records does not
 * mean holding 10<sup>5</sup> records in memory.
 *
 * <h2>What a record is here</h2>
 *
 * A work is identified by one {@link RecordReference}, and what each source said about it is kept separately from the
 * merged view of it. {@link #putRecord} stores the claim and re-merges; the merged view is materialised, so a query
 * reads rows rather than merging as it goes. Keeping the contributions is what lets a later claim merge with the
 * others rather than into an already-merged record, and it is what the metadata clean-up report reads.
 *
 * <h2>Selection</h2>
 *
 * {@link #markSelected} is a mark, never a delete. A rejected record stays in the graph so that PageRank is computed
 * over the real citation network and the decision can be reversed.
 */
// [IMPLEMENTS](/design/features/feat-010-crawl-engine-with-zotero-mirror.md)
public interface RepositoryStore extends AutoCloseable {

	/** What wrote this repository, and when. */
	RepositoryMeta meta();

	/**
	 * Store what one source says about one work, merging it with what is already known.
	 *
	 * Idempotent per source: the same claim twice is one contribution, so a crawl that revisits a record does not
	 * inflate it.
	 */
	void putRecord(Record record);

	/** The merged view of one work, or empty when the repository has never seen it. */
	Optional<Record> getRecord(RecordReference ref);

	/** What each source separately claimed about one work. Empty when the work is unknown. */
	Collection<Record> contributions(RecordReference ref);

	/**
	 * Every record, merged, lazily.
	 *
	 * Hydrated one work at a time rather than in one read, so iterating a large repository costs what is being looked
	 * at rather than what is stored. A caller that only wants the top of it should say so with {@link #topByScore}.
	 */
	Stream<Record> records();

	/** How many works the repository holds, without reading any of them. */
	long recordCount();

	/** Store a citation edge. The same edge twice is one edge; source cites target (the convention from issue-007). */
	void putCitation(CitationLink link);

	/** Every citation edge, lazily. */
	Stream<CitationLink> citations();

	/** How many edges the repository holds. */
	long citationCount();

	/**
	 * The score last assigned to a work by a search or a ranking.
	 *
	 * A property of a question rather than of the work, which is why it is set here rather than carried on
	 * {@link Record}: the repository remembers the most recent answer so that a selection can be made against it
	 * without the model holding an earlier response in its context.
	 */
	void setScore(RecordReference ref, double score);

	/** The highest-scoring works, merged, read from an index rather than by sorting the repository. */
	Stream<Record> topByScore(int limit);

	/** Mark or unmark a work as selected. Nothing is removed either way. */
	void markSelected(RecordReference ref, boolean selected);

	/** The selected works, merged. */
	Stream<Record> selected();

	/**
	 * Store a crawl job, frontier and all.
	 *
	 * The job is its own checkpoint: a crawl writes itself here as it goes, so killing the process resumes from the
	 * frontier rather than from the seeds. Called often, so it replaces the job rather than appending to a history.
	 */
	void putJob(uk.co.terminological.bibliography.crawl.CrawlJob job);

	/** A crawl job as it was last checkpointed. */
	Optional<uk.co.terminological.bibliography.crawl.CrawlJob> job(String id);

	/** Every job this repository knows about, most recently started first. */
	Stream<uk.co.terminological.bibliography.crawl.CrawlJob> jobs();

	@Override
	void close();

}
