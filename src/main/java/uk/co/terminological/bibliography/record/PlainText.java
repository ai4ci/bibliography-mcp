package uk.co.terminological.bibliography.record;

/**
 * Reduces inline markup in a metadata field to plain text.
 *
 * Crossref returns abstracts as JATS XML, connector imports carry publisher HTML, and entity escapes turn up anywhere.
 * None of that is content: an abstract is the sentence it says, and a title with `&amp;` in it says "&". Reducing
 * markup to text is the same question wherever it is asked, so it is asked once — in the record mapping, so every
 * consumer benefits, rather than at each point of use.
 *
 * This is a *fragment* cleaner, not a document parser: a JATS fragment inside a JSON field is not a well-formed
 * document, and nothing here needs a DOM.
 *
 * [IMPLEMENTS](/design/features/feat-008-clean-up-collection-metadata.md)
 */
public class PlainText {

	/** the heading labels that introduce a block rather than being part of it */
	private static final String JATS_HEADING = "(?is)<jats:title>.*?</jats:title>";

	private PlainText() {}

	public static String of(String raw) {
		if (raw == null) return null;
		// a JATS heading is a label ("ABSTRACT"), not the first word of the abstract
		String out = raw.replaceAll(JATS_HEADING, " ");
		// every other tag is markup around the text
		out = out.replaceAll("<[^>]+>", " ");
		out = unescape(out);
		out = out.replaceAll("\\s+", " ")
			.trim();
		return out.isEmpty() ? null : out;
	}

	private static String unescape(String text) {
		return text.replace("&amp;", "&")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&quot;", "\"")
			.replace("&apos;", "'")
			.replace("&#39;", "'")
			.replace("&nbsp;", " ");
	}

	/** whether cleaning would change this value, i.e. it holds markup or escapes */
	public static boolean hasMarkup(String value) {
		if (value == null) return false;
		String cleaned = of(value);
		return cleaned == null || !value.trim()
			.equals(cleaned);
	}
}
