package uk.co.terminological.bibliography.record;

import org.immutables.value.Value;

@Value.Immutable
public interface RecordReferenceMapping {

	RecordReference getSource();
	RecordReference getTarget();

	default boolean isComplete() {
		return this.getSource()
			.isComplete()
				&& this.getTarget()
					.isComplete();
	}

}