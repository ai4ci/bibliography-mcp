package uk.co.terminological.bibliography.client;

import java.util.Collection;

import uk.co.terminological.bibliography.record.Record;

public interface Searcher {

	Collection<? extends Record> search(Query qry);
	SearchBuilder<?> buildQuery();

}
