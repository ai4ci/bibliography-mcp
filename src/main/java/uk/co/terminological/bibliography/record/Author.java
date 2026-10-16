package uk.co.terminological.bibliography.record;

import java.util.Optional;
import java.util.stream.Stream;

import org.immutables.value.Value;

@Value.Immutable
public interface Author {

	Optional<String> getORCID();
	Optional<String> getFirstName();
	String getLastName();
	Optional<String> getInitials();
	Stream<String> getAffiliations();

	default String getLabel() {
		return (this.getLastName() + ", " + this.getInitials()
			.orElse("Unknown")
			.substring(0, 1)).toLowerCase();
	}

	static String print(Author a) {
		return a.getLabel();
	}
}
