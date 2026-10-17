package uk.co.terminological.bibliography.client;

import java.util.Collection;
import java.util.Collections;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * An API that can say what a work cites: its reference list, the outgoing edges.
 *
 * Separate from {@link CitedByMapper} because the two directions are genuinely different capabilities. A publisher
 * deposits the reference list of its own articles and so can always answer this, but has no way of knowing who cited
 * them afterwards; a citation index can answer both; most metadata APIs answer neither. Keeping them apart lets a
 * caller route "expand downwards" to a different set of APIs from "expand upwards", and lets a client advertise only
 * what it can actually do.
 *
 * Answers are {@link CitationLink}s rather than bare references because an entry in a reference list may not be
 * identifiable at all, and because the position in the list is worth keeping. The work asked about is the
 * <em>source</em> of everything returned, since a link always runs from the citing work to the cited one — though not
 * all clients honour that today, see issue-007.
 */
public interface CitesMapper {

	/** The references of a batch of works. One work's list may be empty because it cites nothing, or because this API does not have it. */
	public Collection<? extends CitationLink> citesReferences(
			Collection<? extends RecordReference> ref
	);

	/** Convenience for a single work. */
	default Collection<? extends CitationLink> citesReferences(
			RecordReference ref
	) {
		return citesReferences(Collections.singleton(ref));
	};

}
