package uk.co.terminological.bibliography.record;

import java.util.Optional;
import java.util.function.Consumer;

import org.immutables.value.Value;

/**
 * An identifier for a published work in one of a number of schemes: the minimum needed to name a work, and the
 * identity every other type in this package is built on.
 *
 * Nothing is claimed about the work here beyond which one it is — no title, no metadata. That is what makes it the
 * right type for a citation graph node, for an id-mapping endpoint, and for asking an API to fetch something.
 *
 * The identifier is optional because parsing can fail to produce one, and saying so is better than inventing a
 * placeholder; {@link #isComplete()} is the test, and callers that need a real node drop the rest. Contrast {@link
 * CitationReference}, which can describe a work it cannot identify, and {@link Record}, which adds the claims made
 * about it.
 */
@Value.Immutable
public interface RecordReference {

	/** Absent when the work could not be identified at all. */
	Optional<String> getIdentifier();

	/** The scheme the identifier belongs to; {@link IdType#UNK} for a handle the model does not name. */
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
