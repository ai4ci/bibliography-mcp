package uk.co.terminological.bibliography.client;

import java.util.Collection;
import java.util.Set;

import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordReferenceMapping;

/**
 * An API that can say which other identifiers name the same work.
 *
 * The same article is a DOI to a publisher, a PMID to PubMed, a PMCID to the full-text archive, and a preprint DOI
 * before any of that. Without a mapping source those are four unrelated nodes, and the same paper found by two routes
 * never merges. This is what makes identity in the rest of the model hold together.
 *
 * Note the result type: {@link RecordReferenceMapping} relates two names for <em>one</em> work, which is the opposite
 * of {@link uk.co.terminological.bibliography.record.CitationLink}, whose endpoints are two different works.
 */
public interface IdMapper {

	/**
	 * Equivalences for a batch of identifiers.
	 *
	 * A set rather than a map because one identifier may map to several schemes at once, and the mappings are facts
	 * about pairs rather than answers belonging to a particular question.
	 */
	public Set<? extends RecordReferenceMapping> mappings(
			Collection<? extends RecordReference> source
	);
}
