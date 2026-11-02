package uk.co.terminological.bibliography.record;

import java.time.Instant;
import java.util.Optional;

import org.immutables.value.Value;

/**
 * Where a record's claims came from: which API asserted them, what that API called the kind of work, and optionally
 * when and where it was retrieved.
 *
 * Deliberately separate from identity ({@link IdType}): a Zotero item key, for example, is a handle scoped to one
 * repository instance and belongs here rather than in the identifier scheme.
 *
 * [IMPLEMENTS](/design/features/feat-006-classify-records-by-type-with-provenance.md)
 */
@Value.Immutable
public interface Provenance {

	String CROSSREF = "crossref";
	String ENTREZ = "entrez";
	String EUROPEPMC = "europepmc";
	String MEDRXIV = "medrxiv";
	String BIORXIV = "biorxiv";
	String ARXIV = "arxiv";
	String ZOTERO = "zotero";
	String OPENALEX = "openalex";

	static Provenance of(String source, String nativeType) {
		return ImmutableProvenance.builder()
			.source(source)
			.nativeType(nativeType)
			.isCurated(false)
			.build();
	}

	static Provenance of(String source, String nativeType, Instant fetchedAt) {
		return ImmutableProvenance.builder()
			.source(source)
			.nativeType(nativeType)
			.fetchedAt(fetchedAt)
			.isCurated(false)
			.build();
	}

	static Provenance canonical(String source, Optional<String> type) {
		return ImmutableProvenance.builder()
			.source(source)
			.nativeType(type)
			.isCanonical(true)
			.build();
	}

	static Provenance placeholder(String source) {
		return ImmutableProvenance.builder()
			.source(source)
			.build();
	}

	/**
	 * A record the user curated (a Zotero item). Such records are never merged into API-derived records — see feat-007 /
	 * plan-003.
	 */
	static Provenance curated(String source) {
		return ImmutableProvenance.builder()
			.source(source)
			.isCanonical(true)
			.isCurated(true)
			.build();
	}

	String getSource();
	/** the source's own vocabulary, e.g. CrossRef {@code journal-article}, Zotero {@code journalArticle} */
	Optional<String> getNativeType();
	@Value.Auxiliary
	Optional<Instant> getFetchedAt();

	@Value.Default
	default boolean isCurated() { return false; }
	@Value.Default
	default boolean isCanonical() { return false; }

}
