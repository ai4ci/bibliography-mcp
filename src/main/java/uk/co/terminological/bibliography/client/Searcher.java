package uk.co.terminological.bibliography.client;

import java.util.Collection;

import uk.co.terminological.bibliography.record.Record;

/**
 * An API that can be asked an open question: terms in, candidate works out.
 *
 * This is discovery, as distinct from {@link RecordFetcher}, which resolves something already named. A searcher makes
 * no promise that its results are the same works another searcher would return, so the results of several are merged
 * by identifier downstream rather than trusted to agree.
 */
public interface Searcher {

	/**
	 * Run a structured query, with each implementation rendering the {@link Query} into its own syntax. Results come
	 * back as the common {@link Record} shape; use {@link #buildQuery()} instead when the API's own result type is
	 * wanted.
	 */
	Collection<? extends Record> search(Query qry);

	/** A fluent search returning this API's own result type rather than the common record shape. */
	SearchBuilder<?> buildQuery();

}
