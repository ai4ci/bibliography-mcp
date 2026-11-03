package uk.co.terminological.bibliography.api;

import java.util.List;

/**
 * What a multi-API search found, and what it could not ask.
 *
 * These are third-party network services: rate limiting, transient 5xx, maintenance and endpoint drift are operating
 * conditions rather than exceptions. A search that loses everything because one source threw is worse than one that
 * returns what it has — and a search that silently returns three APIs' worth of results when four were asked is worse
 * still, because the caller cannot tell that its coverage was reduced (issue-013).
 *
 * So the failures travel with the results. The {@code Searcher} SPI is unchanged: a single client still answers with
 * records alone, and this is the facade's account of asking several of them.
 */
public record SearchOutcome(List<ScoredRecord> results, List<ApiFailure> failures) {

	/** one source that could not be asked, and what it said */
	public record ApiFailure(String api, String reason) {}

	public static SearchOutcome of(
			List<ScoredRecord> results, List<ApiFailure> failures
	) {
		return new SearchOutcome(List.copyOf(results), List.copyOf(failures));
	}

	/** Whether every source answered. */
	public boolean complete() { return this.failures.isEmpty(); }

}
