package uk.co.terminological.bibliography.record;

import java.util.Optional;

import org.immutables.value.Value;

/**
 * Describes the physical location of the publication in a journal
 */
@Value.Immutable
public interface PrintReference {

	Optional<String> getFirstAuthorName();
	Optional<String> getJournal();
	Optional<String> getVolume();
	Optional<String> getIssue();
	Optional<Long> getYear();
	Optional<String> getPage();

}
