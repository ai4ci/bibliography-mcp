package uk.co.terminological.bibliography.client;

import java.util.Collection;
import java.util.Collections;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.RecordReference;

public interface CitesMapper {

	public Collection<? extends CitationLink> citesReferences(
			Collection<? extends RecordReference> ref
	);

	default Collection<? extends CitationLink> citesReferences(
			RecordReference ref
	) {
		return citesReferences(Collections.singleton(ref));
	};

}
