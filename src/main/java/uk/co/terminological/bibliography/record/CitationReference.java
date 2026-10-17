package uk.co.terminological.bibliography.record;

import java.util.Optional;

import org.immutables.value.Value;

/**
 * A work as it appears in someone's reference list — which may be a work we cannot identify.
 *
 * This is the one reference type that is <strong>not</strong> a {@link RecordReference}, and the distinction is the
 * point. A reference list entry frequently carries no identifier at all: "Smith J et al. Lancet 2011;377:1234-42"
 * names a real paper perfectly well, and roughly half of what the citation APIs return looks like that. A type that
 * required an identifier would force such an entry to be discarded at parse time, before anything could try to
 * resolve it.
 *
 * So it holds an optional identifier plus the two things available when there is none: the title, and the print
 * coordinates. {@link #isComplete()} separates the references that can become graph nodes from the ones that can only
 * be displayed.
 */
@Value.Immutable
public interface CitationReference {

	/** Absent when the source named the work without identifying it. */
	Optional<RecordReference> getIdentifier();
	/** The cited title, where the source gave one; the main fallback when there is no identifier. */
	Optional<String> getTitle();
	/**
	 * Journal, volume, issue, page and year as cited — enough to find the work by hand, and enough for a citation
	 * processor to render it. Populated only by the sources that supply structured reference lists, and currently read
	 * by nothing: see debt-011.
	 */
	Optional<PrintReference> getBibliographicId();

	/** True when this names a work we could look up, rather than only one we could print. */
	default boolean isComplete() {
		return this.getIdentifier()
			.isPresent()
				&& this.getIdentifier()
					.get()
					.isComplete();
	}

//	static CitationReference create(
//			final RecordReference identifier, final String title,
//			final PrintReference bibliographicInfo
//	) {
//		return new CitationReference() {
//
//			@Override
//			public Optional<RecordReference> getIdentifier() {
//				return Optional.ofNullable(identifier);
//			}
//
//			@Override
//			public Optional<String> getTitle() {
//				return Optional.ofNullable(title);
//			}
//
//			@Override
//			public Optional<PrintReference> getBibliographicId() {
//				return Optional.ofNullable(bibliographicInfo);
//			}
//
//		};
//	}

	static String print(CitationReference cr) {
		return cr.getTitle()
			.orElse("No title") + " "
				+ cr.getIdentifier()
					.map(RecordReference::print)
					.orElse("No identifier");

	}

}
