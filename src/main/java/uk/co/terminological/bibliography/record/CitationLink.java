package uk.co.terminological.bibliography.record;

import java.util.Optional;

import org.immutables.value.Value;

@Value.Immutable
public interface CitationLink {
	CitationReference getSource();
	CitationReference getTarget();
	Optional<Integer> getIndex();

	default boolean isComplete() {
		return this.getSource()
			.isComplete()
				&& this.getTarget()
					.isComplete();
	}

	static String print(CitationLink cl) {
		return cl.getIndex()
			.map(s -> s + ") ")
			.orElse("")
				+ cl.getSource()
					.getIdentifier()
					.map(RecordReference::print)
					.orElse("Unknown source")
				+ " > " + cl.getTarget()
					.getIdentifier()
					.map(RecordReference::print)
					.orElse("Unknown target");
	}
}
