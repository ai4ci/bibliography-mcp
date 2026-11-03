package uk.co.terminological.bibliography.crawl;

/**
 * Where a crawl job has got to.
 *
 * {@code DONE} covers a job that finished its frontier and one that stopped at a ceiling: a crawl that ran out of
 * budget has not failed, and {@code CrawlJob#stoppedBy()} says which it was so that status explains itself rather than
 * looking truncated.
 */
public enum JobStatus {
	/** created, not yet picked up by the executor */
	PENDING,
	RUNNING,
	/** the frontier drained, or a ceiling was reached */
	DONE,
	CANCELLED,
	/** the engine itself threw; a failing API marks a record and carries on */
	FAILED
}
