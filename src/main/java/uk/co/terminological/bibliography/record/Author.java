package uk.co.terminological.bibliography.record;

import java.util.Optional;
import java.util.stream.Stream;

import org.immutables.value.Value;

/**
 * A contributor to a work.
 *
 * The surname is the only required field, because it is the only one every source supplies: some give full forenames,
 * some only initials, and an ORCID appears on a minority. Requiring more would mean inventing it.
 *
 * That makes identity awkward and the awkwardness is deliberate rather than hidden: an ORCID identifies a person, and
 * without one the best available key is a normalised name, which merges two people who share one. {@code MergedRecord}
 * de-duplicates on that basis and says so.
 */
@Value.Immutable
public interface Author {

	/** The only field that identifies a person rather than describing them; present on a minority of records. */
	Optional<String> getORCID();
	Optional<String> getFirstName();
	/** Required: the one part of a name every source supplies. */
	String getLastName();
	Optional<String> getInitials();
	Stream<String> getAffiliations();

	/** A lower-case "surname, initial" label, used as the name-based identity key when there is no ORCID. */
	default String getLabel() {
		return (this.getLastName() + ", " + this.getInitials()
			.orElse("Unknown")
			.substring(0, 1)).toLowerCase();
	}

	static String print(Author a) {
		return a.getLabel();
	}
}
