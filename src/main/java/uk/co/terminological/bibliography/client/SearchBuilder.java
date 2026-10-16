package uk.co.terminological.bibliography.client;

import java.util.Optional;

public interface SearchBuilder<X> {
	public SearchBuilder<X> withQuery(Query qry);
	public Optional<X> execute();
}