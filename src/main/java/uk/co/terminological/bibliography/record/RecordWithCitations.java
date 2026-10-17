package uk.co.terminological.bibliography.record;

import java.util.List;
import java.util.Optional;

import org.immutables.value.Value;

/**
 * A record together with the citation edges that arrived in the same payload.
 *
 * Separate from {@link Record} rather than a method on it because most fetches do not return a reference list: making
 * every record answer {@code getCitations()} would leave an empty list meaning both "this work cites nothing" and
 * "nobody asked". Carrying the distinction in the type keeps that honest, and keeps the cost of building the links out
 * of the plain fetch path.
 *
 * Not every edge here will be usable — reference lists contain entries that cannot be identified — so consumers
 * building a graph filter on {@link CitationLink#isComplete()}.
 */
@Value.Immutable
public interface RecordWithCitations extends Record {

	/** The citations supplied with this record, this work being the citing end of each. */
	List<CitationLink> getCitations();

	/**
	 * How many works cite this one, where the source reports a total. Not derivable from {@link #getCitations()}, which
	 * holds the other direction, and absent rather than zero when unknown.
	 */
	default Optional<Long> getCitedByCount() { return Optional.empty(); }

	/** How many works this one cites. Defaults to counting the links in hand, which a source with a true total overrides. */
	default Optional<Long> getReferencesCount() {
		return Optional.of(
			(long) this.getCitations()
				.size()
		);
	}

}
