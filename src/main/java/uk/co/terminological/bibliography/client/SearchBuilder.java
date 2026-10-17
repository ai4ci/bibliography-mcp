package uk.co.terminological.bibliography.client;

import java.util.Optional;

/**
 * A fluent search that yields an API's own result type, for callers who want the raw shape rather than the common
 * {@link uk.co.terminological.bibliography.record.Record} view.
 *
 * {@link Searcher#search} answers "which works match" in the shared vocabulary, which is what the session and graph
 * code needs. This answers "what did this API actually say", which is what a caller reaching for a field the common
 * model does not carry needs. Parameterised on the result type {@code X} so nothing is flattened away.
 *
 * @param <X> this API's own result type
 */
public interface SearchBuilder<X> {
	public SearchBuilder<X> withQuery(Query qry);

	/** Empty when the query could not be run or the API returned nothing usable; these clients do not throw on a miss. */
	public Optional<X> execute();
}