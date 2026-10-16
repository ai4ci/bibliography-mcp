package uk.co.terminological.bibliography.client;

import java.util.Collection;
import java.util.Set;

import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordReferenceMapping;

public interface IdMapper {

	public Set<? extends RecordReferenceMapping> mappings(
			Collection<? extends RecordReference> source
	);
}
