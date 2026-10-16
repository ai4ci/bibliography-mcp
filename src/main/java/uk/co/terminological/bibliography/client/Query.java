package uk.co.terminological.bibliography.client;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.immutables.value.Value;

@Value.Immutable
public interface Query {

	Optional<String> getNativeQuery();
	Optional<String> getAllFields();
	Optional<String> getTitleAbstract();
	List<String> getFullText();
	List<String> getAuthors();
	Optional<String> getJournal();
	Optional<Integer> getLimit();
	Optional<Integer> getYear();

	@Value.Default
	default Optional<LocalDate> getAfter() {
		return this.getYear()
			.map(y -> LocalDate.of(y, 1, 1));
	}

	@Value.Default
	default Optional<LocalDate> getUntil() {
		return this.getYear()
			.map(y -> LocalDate.of(y, 12, 31));
	}

}
