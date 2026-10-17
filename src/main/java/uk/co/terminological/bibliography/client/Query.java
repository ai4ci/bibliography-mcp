package uk.co.terminological.bibliography.client;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.immutables.value.Value;

/**
 * A search expressed as intent rather than as a string, so that one query can be put to several APIs.
 *
 * The query syntaxes are mutually incompatible — Entrez wants {@code term[tiab]}, CrossRef wants {@code query.title},
 * Europe PMC wants its own — so a search string is always written for one specific API and cannot be routed. Here the
 * caller states which terms go where, and each {@link Searcher} renders that into its own syntax, degrading where it
 * must: an API with no full-text index can only approximate {@link #getFullText()}, and says so by returning fewer
 * results rather than by failing.
 *
 * Every field is optional because every API supports a different subset.
 */
@Value.Immutable
public interface Query {

	/**
	 * The target API's own syntax, passed through untouched.
	 *
	 * The deliberate escape hatch for a caller who knows which API they are talking to and wants something the
	 * structured fields cannot express. Not portable — a native query routed to several APIs means something different
	 * to each, or nothing at all.
	 */
	Optional<String> getNativeQuery();

	/** Terms to match anywhere the API indexes. */
	Optional<String> getAllFields();

	/** Terms to match in title or abstract: the usual topic search, and the most widely supported restriction. */
	Optional<String> getTitleAbstract();

	/** Terms to match in the body of the work. Only some sources index full text; the rest approximate or ignore it. */
	List<String> getFullText();

	List<String> getAuthors();
	Optional<String> getJournal();

	/** Maximum results wanted. Clients page up to this rather than fetching everything and truncating. */
	Optional<Integer> getLimit();

	/** A single publication year, as the common shorthand for the {@link #getAfter()}/{@link #getUntil()} window. */
	Optional<Integer> getYear();

	/** Start of the publication date window; defaults to the first day of {@link #getYear()} when only a year was given. */
	@Value.Default
	default Optional<LocalDate> getAfter() {
		return this.getYear()
			.map(y -> LocalDate.of(y, 1, 1));
	}

	/** End of the publication date window; defaults to the last day of {@link #getYear()} when only a year was given. */
	@Value.Default
	default Optional<LocalDate> getUntil() {
		return this.getYear()
			.map(y -> LocalDate.of(y, 12, 31));
	}

}
