package uk.co.terminological.bibliography.crawl;

import java.util.Collection;
import java.util.Map;

import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * What a crawl needs from the outside world: edges in each direction, and the records at the ends of them.
 *
 * Narrower than {@code BiblioFacade}, which implements it, so the engine can be exercised over a citation graph
 * declared in a test rather than fetched. The engine never learns which APIs answered — that is the facade's business,
 * including the budget gates and the per-client direction conventions.
 */
public interface CrawlSource {

	/** What these works cite: the outgoing edges, where the work asked about is the citing end. */
	Collection<? extends CitationLink> citesReferences(
			Collection<? extends RecordReference> refs
	);

	/** What cites these works: the incoming edges, where the work asked about is the cited end. */
	Collection<? extends CitationLink> referencesCiting(
			Collection<? extends RecordReference> refs
	);

	/** The records for these identifiers, merged across whatever sources answered. */
	Map<ImmutableRecordReference, ? extends Record> fetch(
			Collection<? extends RecordReference> ids
	);

}
