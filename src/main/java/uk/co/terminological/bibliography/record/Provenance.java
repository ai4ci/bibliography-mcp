package uk.co.terminological.bibliography.record;

import java.io.Serializable;
import java.time.Instant;
import java.util.Optional;

/**
 * Where a record's claims came from: which API asserted them, what that API
 * called the kind of work, and optionally when and where it was retrieved.
 *
 * Deliberately separate from identity ({@link IdType}): a Zotero item key, for
 * example, is a handle scoped to one repository instance and belongs here
 * rather than in the identifier scheme.
 *
 * [IMPLEMENTS](/design/features/feat-006-classify-records-by-type-with-provenance.md)
 */
public class Provenance implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String CROSSREF = "crossref";
	public static final String ENTREZ = "entrez";
	public static final String EUROPEPMC = "europepmc";
	public static final String UNPAYWALL = "unpaywall";
	public static final String ZOTERO = "zotero";

	private final String source;
	private final String nativeType;
	private final Instant fetchedAt;
	private final String url;
	private final boolean curated;

	private Provenance(String source, String nativeType, Instant fetchedAt, String url, boolean curated) {
		this.source = source;
		this.nativeType = nativeType;
		this.fetchedAt = fetchedAt;
		this.url = url;
		this.curated = curated;
	}

	public static Provenance of(String source, String nativeType) {
		return new Provenance(source, nativeType, null, null, false);
	}

	public static Provenance of(String source, String nativeType, Instant fetchedAt, String url) {
		return new Provenance(source, nativeType, fetchedAt, url, false);
	}

	/**
	 * A record the user curated (a Zotero item). Such records are never merged
	 * into API-derived records — see feat-007 / plan-003.
	 */
	public static Provenance curated(String source) {
		return new Provenance(source, null, null, null, true);
	}

	public String getSource() { return source; }

	/** the source's own vocabulary, e.g. CrossRef {@code journal-article}, Zotero {@code journalArticle} */
	public Optional<String> getNativeType() { return Optional.ofNullable(nativeType); }

	public Optional<Instant> getFetchedAt() { return Optional.ofNullable(fetchedAt); }

	public Optional<String> getUrl() { return Optional.ofNullable(url); }

	public boolean isCurated() { return curated; }

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof Provenance)) return false;
		Provenance that = (Provenance) other;
		return curated == that.curated
				&& java.util.Objects.equals(source, that.source)
				&& java.util.Objects.equals(nativeType, that.nativeType)
				&& java.util.Objects.equals(url, that.url);
	}

	@Override
	public int hashCode() { return java.util.Objects.hash(source, nativeType, url, curated); }

	@Override
	public String toString() {
		return "Provenance[" + source + (nativeType == null ? "" : " " + nativeType) + (curated ? " curated" : "") + "]";
	}
}
