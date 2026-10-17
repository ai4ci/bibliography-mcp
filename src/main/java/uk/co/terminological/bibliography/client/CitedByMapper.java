package uk.co.terminological.bibliography.client;

import java.util.Collection;
import java.util.Collections;

import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * An API that can say what cites a work: the incoming edges.
 *
 * The harder of the two directions, and the reason {@link CitesMapper} is a separate interface — answering it requires
 * an index over everyone else's reference lists, which only a citation index has. It is also open-ended in a way the
 * outgoing direction is not: a reference list is finite and fixed at publication, whereas the set of works citing
 * something grows indefinitely, so callers are expected to bound the expansion.
 */
public interface CitedByMapper {

	/**
	 * The citations of a batch of works.
	 *
	 * Asking the question in this direction does not reverse the edges: a {@link CitationLink} always runs from the
	 * citing work to the cited one, so here the work asked about is the <em>target</em> of everything returned. Not all
	 * clients honour that today — see issue-007.
	 */
	public Collection<? extends CitationLink> referencesCiting(
			Collection<? extends RecordReference> ref
	);

	/** Convenience for a single work. */
	default Collection<? extends CitationLink> referencesCiting(
			RecordReference ref
	) {
		return referencesCiting(Collections.singleton(ref));
	};

}
