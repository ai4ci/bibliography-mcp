package uk.co.terminological.bibliography.record;

import java.util.Optional;

import org.immutables.value.Value;

/**
 * One citation: a directed edge between two works, running <strong>from the citing work to the cited one</strong>.
 *
 * The direction is a contract, not an implementation detail, and the accessor names do not imply it on their own: the
 * source cites the target, whichever way round the question was asked. A "what cites this" query therefore returns
 * links whose <em>target</em> is the work asked about. The citation graph is built directly from these edges, and
 * page rank scores a node by its incoming ones, so a reversed edge does not merely look odd — it pushes importance the
 * wrong way. (issue-007 records that the clients do not all honour this yet.)
 *
 * Both endpoints are {@link CitationReference}s rather than {@link RecordReference}s because reference lists contain
 * entries that cannot be identified; {@link #isComplete()} is how a caller that needs two real graph nodes filters
 * those out.
 *
 * Distinct from {@link RecordReferenceMapping}, which has the same shape and the opposite meaning: this relates two
 * different works, that one relates two names for the same work.
 */
@Value.Immutable
public interface CitationLink {
	/** the citing work */
	CitationReference getSource();
	/** the cited work */
	CitationReference getTarget();
	/**
	 * Position in the citing work's reference list, where the source supplied one. Only meaningful for links obtained by
	 * reading a work's own references — a "cited by" answer has no list to be a position in.
	 */
	Optional<Integer> getIndex();

	/** True when both ends name a work, i.e. the edge can be added to a graph. */
	default boolean isComplete() {
		return this.getSource()
			.isComplete()
				&& this.getTarget()
					.isComplete();
	}

	static String print(CitationLink cl) {
		return cl.getIndex()
			.map(s -> s + ") ")
			.orElse("")
				+ cl.getSource()
					.getIdentifier()
					.map(RecordReference::print)
					.orElse("Unknown source")
				+ " > " + cl.getTarget()
					.getIdentifier()
					.map(RecordReference::print)
					.orElse("Unknown target");
	}
}
