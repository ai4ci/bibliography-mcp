package uk.co.terminological.bibliography.client;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.MergedRecord;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * An API that can resolve an identifier into what it knows about that work.
 *
 * The counterpart to {@link Searcher}: this answers "tell me about this", not "find me something". Nearly every
 * source implements it, but the coverage is not uniform: a DOI-keyed source such as PMC IDConverter resolves only one
 * identifier type, which is what debt-005 is about.
 */
public interface RecordFetcher {

	/**
	 * Fetch records for a batch of identifiers, keyed by the identifier that found each one.
	 *
	 * The argument is named {@code equivalentIds} because the usual caller passes several identifiers believed to name
	 * the <em>same</em> work (a DOI and its PMID, say), and an API may answer to one and not another. The result is a
	 * map rather than a collection so the caller can tell which key produced which answer, and an identifier that
	 * resolved to nothing is simply absent rather than mapped to null.
	 */
	Map<ImmutableRecordReference, ? extends Record> fetch(
			Collection<? extends RecordReference> equivalentIds
	);

	/** Convenience for a single identifier. Empty when this API has nothing for it. */
	default Optional<? extends Record> fetch(IdType type, String id) {
		ImmutableRecordReference tmp = Builder.recordReference(type, id);
		return fetch(Collections.singleton(tmp)).values()
			.stream()
			.collect(MergedRecord.collector());
	};

	/**
	 * Re-fetch a record by every identifier it carries, merging whatever comes back.
	 *
	 * A record found by one identifier often carries others, and an API that could not answer to the first may answer to
	 * one of the rest, so asking with all of them is strictly better than asking with the one in hand. The answers are
	 * collected through {@link MergedRecord} because several of them may describe the same work.
	 */
	default Optional<? extends Record> fetch(Record rec) {
		Set<RecordReference> tmp = new HashSet<>();
		tmp.add(rec);
		tmp.addAll(rec.getOtherIdentifiers());
		return fetch(tmp).values()
			.stream()
			.collect(MergedRecord.collector());
	};

}
