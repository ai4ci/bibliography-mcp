package uk.co.terminological.bibliography.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Markup inside a metadata field is not content: CrossRef returns abstracts as JATS
 * XML, and connector imports carry publisher HTML. Reducing it to text is the same
 * question wherever it is asked, so it is asked once, here.
 *
 * [TESTS](/design/test-scripts/test-004-metadata-cleanup-tests.md)
 */
public class TestPlainText {

	@Test
	public void jatsTagsAreRemoved() {
		assertEquals("We studied things.", PlainText.of("<jats:p>We studied things.</jats:p>"));
	}

	@Test
	public void aJatsHeadingGoesWithItsContent() {
		// "ABSTRACT" is the label of the abstract, not the first word of it
		assertEquals("We studied things.",
				PlainText.of("<jats:title>ABSTRACT</jats:title><jats:p>We studied things.</jats:p>"));
	}

	@Test
	public void htmlTagsAreRemovedToo() {
		assertEquals("A title with emphasis and more",
				PlainText.of("A title with <i>emphasis</i> and <b>more</b>"));
	}

	@Test
	public void entitiesAreUnescaped() {
		assertEquals("Health & social care: a review", PlainText.of("Health &amp; social care: a review"));
		assertEquals("Use of <angle> brackets", PlainText.of("Use of &lt;angle&gt; brackets"));
		assertEquals("It's a \"quote\"", PlainText.of("It&#39;s a &quot;quote&quot;"));
	}

	@Test
	public void whitespaceIsCollapsed() {
		assertEquals("One two three", PlainText.of("  One\n\ttwo   three  "));
		// a block tag leaves a gap where it was
		assertEquals("One two", PlainText.of("<jats:p>One</jats:p><jats:p>two</jats:p>"));
	}

	@Test
	public void plainTextIsLeftAlone() {
		assertEquals("Nothing to do here.", PlainText.of("Nothing to do here."));
	}

	@Test
	public void nothingBecomesNull() {
		assertNull(PlainText.of(null));
		assertNull(PlainText.of(""));
		assertNull(PlainText.of("   "));
		assertNull(PlainText.of("<jats:p></jats:p>"));
	}
}
