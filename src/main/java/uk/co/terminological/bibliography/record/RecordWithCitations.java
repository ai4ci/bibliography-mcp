package uk.co.terminological.bibliography.record;

import java.util.List;
import java.util.Optional;

import org.immutables.value.Value;

@Value.Immutable
public interface RecordWithCitations extends Record {

	List<CitationLink> getCitations();
	default Optional<Long> getCitedByCount() { return Optional.empty(); }
	default Optional<Long> getReferencesCount() {
		return Optional.of(
			(long) this.getCitations()
				.size()
		);
	}

}
