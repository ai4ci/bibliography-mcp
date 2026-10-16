package uk.co.terminological.bibliography.client;

import java.util.Collection;
import java.util.Collections;

import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.RecordReference;

public interface CitedByMapper {

	public Collection<? extends CitationLink> referencesCiting(
			Collection<? extends RecordReference> ref
	);

	default Collection<? extends CitationLink> referencesCiting(
			RecordReference ref
	) {
		return referencesCiting(Collections.singleton(ref));
	};

}
