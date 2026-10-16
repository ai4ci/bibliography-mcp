package uk.co.terminological.bibliography.api.zotero;

import java.util.Locale;

/**
 * Normalisation rules used both for mapping Zotero identifiers and for matching library items against records from
 * other APIs.
 *
 * Deliberately simple and exact: text is reduced to lowercase alphanumerics and compared exactly. Anything cleverer
 * (similarity, fuzzy dates) would make "already in my library" unpredictable, and the decision of what to do about a
 * near miss belongs to the caller — see the write rules in feat-005.
 *
 * [IMPLEMENTS](/design/features/feat-005-zotero-curated-repository-bridge.md)
 */
public class ZoteroMatching {

	/** DOI without a resolver prefix or URL decoration, lowercased. */
	public static String doi(String raw) {
		if (raw == null) return null;
		String out = raw.trim()
			.toLowerCase(Locale.ROOT);
		out = out.replaceFirst("^https?://(dx\\.)?doi\\.org/", "");
		out = out.replaceFirst("^doi:", "");
		return out.isEmpty() ? null : out;
	}

	/** ISBN without hyphens or spaces, lowercased (so a trailing "X" compares). */
	public static String isbn(String raw) {
		if (raw == null) return null;
		String out = raw.replaceAll("[^0-9Xx]", "")
			.toLowerCase(Locale.ROOT);
		return out.isEmpty() ? null : out;
	}

	/** Lowercase alphanumerics only — punctuation, case and spacing differences do not matter. */
	public static String text(String raw) {
		if (raw == null) return null;
		String out = raw.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]", "");
		return out.isEmpty() ? null : out;
	}

	/** A title+year key, so items with no identifier at all can still be matched. */
	public static String titleYear(String title, Integer year) {
		String t = text(title);
		if (t == null || year == null) return null;
		return t + "|" + year;
	}
}
