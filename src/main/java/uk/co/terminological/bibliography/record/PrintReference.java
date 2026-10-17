package uk.co.terminological.bibliography.record;

import java.util.Optional;

import org.immutables.value.Value;

/**
 * Where a work physically sits in the literature: journal, volume, issue, page, year, first author surname.
 *
 * A coordinate, not an identifier scheme — which is why it is separate from {@link RecordReference}. Two works can
 * share a volume and issue, and a coordinate cannot be looked up in an API, but it is how the literature referred to
 * itself before DOIs and it is what a citation processor needs in order to render "Smith et al., Lancet
 * 377(9775):1234".
 *
 * It has two jobs. Inside a {@link CitationReference} it is the fallback description of a work that carries no
 * identifier. Mixed into {@link PrintRecord} it is the extra detail from sources that supply pagination alongside the
 * metadata.
 */
@Value.Immutable
public interface PrintReference {

	/** Surname only, as reference lists give it; enough for "Smith et al.". */
	Optional<String> getFirstAuthorName();
	Optional<String> getJournal();
	Optional<String> getVolume();
	Optional<String> getIssue();
	Optional<Long> getYear();
	Optional<String> getPage();

}
