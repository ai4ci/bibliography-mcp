package uk.co.terminological.bibliography.record;

import org.immutables.value.Value;

/**
 * An assertion that two identifiers name the <strong>same work</strong>: a DOI and its PMID, a PMID and its PMCID, a
 * preprint and the article it became.
 *
 * Without these the model has no way to tell that the CrossRef result and the PubMed result are the same paper, and
 * the same work found by two routes stays two records with two sets of citations. It is what {@code Session} uses to
 * decide that two identifiers should collapse onto one merged record.
 *
 * Deliberately the same shape as {@link CitationLink} and deliberately the opposite meaning — that one relates two
 * different works, this one relates two names for one work. Treating a mapping as a citation produces a self-loop;
 * treating a citation as a mapping merges two papers into one.
 *
 * Both endpoints are {@link RecordReference}s rather than {@link CitationReference}s because an equivalence between
 * identifiers is meaningless if an identifier is missing — unlike a citation, which can still name a work by title.
 */
@Value.Immutable
public interface RecordReferenceMapping {

	/** the identifier that was asked about */
	RecordReference getSource();
	/** an equivalent identifier for the same work, in another scheme */
	RecordReference getTarget();

	/** True when both ends carry an identifier; an equivalence missing one asserts nothing. */
	default boolean isComplete() {
		return this.getSource()
			.isComplete()
				&& this.getTarget()
					.isComplete();
	}

}