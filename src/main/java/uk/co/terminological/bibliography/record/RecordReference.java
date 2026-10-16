package uk.co.terminological.bibliography.record;

import java.util.Optional;
import java.util.function.Consumer;

import org.immutables.value.Value;

/**
 * An identifier for a published work in one of a number of schemes.
 */
@Value.Immutable
public interface RecordReference {

	Optional<String> getIdentifier();
	IdType getIdentifierType();

	default ImmutableRecordReference asId() {
		return Builder.recordReference(this);
	}

	static String print(RecordReference rr) {
		return rr.getIdentifierType()
			.toString() + ":"
				+ rr.getIdentifier()
					.orElse("unknown");
	}

	default boolean isComplete() { return this.getIdentifier()
		.isPresent(); }

	default void ifComplete(Consumer<ImmutableRecordReference> c) {
		if (this.getIdentifier()
			.isPresent()) {
			c.accept(Builder.recordReference(this));
		}
	}
}
