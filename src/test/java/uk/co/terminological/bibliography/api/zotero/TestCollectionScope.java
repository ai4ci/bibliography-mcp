package uk.co.terminological.bibliography.api.zotero;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * Which collections a write may touch, when the target is a pattern rather than one key.
 *
 * A single key was too constraining: it names one collection, and a human who thinks of their library as "everything
 * under api-" has to look a key up to say so. A pattern describes a <em>region</em> of the collection tree, and the
 * two questions that follow are different — may this write happen (anywhere in the region), and where should a new
 * sub-collection go (the region's root).
 *
 * The library tree is passed in, so these assert the rules rather than a running Zotero.
 *
 * [TESTS](/design/test-scripts/test-003-zotero-bridge-tests.md)
 */
public class TestCollectionScope {

	/** the developer's actual shape: one top-level collection with children under it */
	private static List<ZoteroCollection> library() {
		return List.of(
			new ZoteroCollection("TB35FFX6", "api-test", null),
			new ZoteroCollection("I5P5XZ47", "api-test-bridge-check", "TB35FFX6"),
			new ZoteroCollection("VVTFV52H", "api-test-bridge-check2", "TB35FFX6"),
			new ZoteroCollection("62JAPEDP", "api-test attachment run 1", "TB35FFX6"),
			new ZoteroCollection("REALWORK", "Antimicrobial resistance", null),
			new ZoteroCollection("REALSUB1", "2026 reading", "REALWORK")
		);
	}

	// ---- what the pattern matches ----

	@Test
	public void aWildcardMatchesOnTheCollectionName() {
		assertTrue(CollectionScope.matches("api-*", "api-test"));
		assertTrue(CollectionScope.matches("api-*", "api-test-bridge-check"));
		assertFalse(CollectionScope.matches("api-*", "Antimicrobial resistance"));
		// a prefix is a prefix: "api-" is not "apiary"
		assertFalse(CollectionScope.matches("api-*", "apiary"));
	}

	@Test
	public void matchingIgnoresCase() {
		// collection names are typed by a human, and nobody wants to lose a write to
		// a capital letter
		assertTrue(CollectionScope.matches("api-*", "API-Test"));
		assertTrue(CollectionScope.matches("API-*", "api-test"));
	}

	@Test
	public void aPatternWithoutAWildcardStillMeansOneThing() {
		// the property used to be a collection key and files in the wild still carry
		// one, so a bare value matches a key or an exact name and nothing else
		assertTrue(CollectionScope.matches("TB35FFX6", "TB35FFX6"));
		assertTrue(CollectionScope.matches("api-test", "api-test"));
		assertFalse(CollectionScope.matches("api-test", "api-test-bridge-check"));
	}

	@Test
	public void aQuestionMarkMatchesOneCharacter() {
		assertTrue(CollectionScope.matches("api-test?", "api-test2"));
		assertFalse(CollectionScope.matches("api-test?", "api-test"));
	}

	@Test
	public void theCharactersOfARegexAreNotMagic() {
		// a collection called "Smith (2024)" must not be read as a regular expression
		assertTrue(CollectionScope.matches("Smith (2024)", "Smith (2024)"));
		assertFalse(CollectionScope.matches("Smith (2024)", "Smith 2024"));
		assertTrue(CollectionScope.matches("Smith*", "Smith (2024)"));
	}

	// ---- may this write happen ----

	@Test
	public void aCollectionMatchingThePatternMayBeWrittenTo() {
		assertTrue(
			CollectionScope.permits("api-*", "TB35FFX6", library())
		);
	}

	@Test
	public void anythingInsideTheRegionMayBeWrittenTo() {
		// a sub-collection created by the bridge is a child of the target, and it
		// must stay writable even when its own name does not match
		var withOddChild = new java.util.ArrayList<>(library());
		withOddChild
			.add(new ZoteroCollection("ODDNAME1", "October results", "TB35FFX6"));

		assertTrue(
			CollectionScope.permits("api-*", "ODDNAME1", withOddChild)
		);
	}

	@Test
	public void anythingOutsideTheRegionMayNot() {
		assertFalse(CollectionScope.permits("api-*", "REALWORK", library()));
		assertFalse(CollectionScope.permits("api-*", "REALSUB1", library()));
		assertFalse(
			"a collection the library does not have is not writable either",
			CollectionScope.permits("api-*", "NOSUCHKEY", library())
		);
	}

	@Test
	public void anEmptyPatternPermitsNothing() {
		// the refusal that stands between a crawl and someone's whole library
		assertFalse(CollectionScope.permits("", "TB35FFX6", library()));
		assertFalse(CollectionScope.permits(null, "TB35FFX6", library()));
	}

	@Test
	public void aPatternThatWouldMatchEverythingIsStillOnlyWhatItMatches() {
		assertTrue(CollectionScope.permits("*", "REALWORK", library()));
	}

	// ---- where does a new sub-collection go ----

	@Test
	public void theRegionHasOneRootEvenWhenSeveralCollectionsMatch() {
		// api-* matches four collections, but three of them are inside the fourth:
		// a new sub-collection belongs under the top of the region, not under a leaf
		var roots = CollectionScope.roots("api-*", library());

		assertEquals(1, roots.size());
		assertEquals("TB35FFX6", roots.get(0)
			.getKey());
	}

	@Test
	public void twoUnrelatedMatchesAreAmbiguousRatherThanGuessedAt() {
		var two = new java.util.ArrayList<>(library());
		two.add(new ZoteroCollection("OTHERTOP", "api-other", null));

		var roots = CollectionScope.roots("api-*", two);

		assertEquals(
			"both are tops of their own region, and choosing between them is not"
					+ " this code's decision",
			2, roots.size()
		);
	}

	@Test
	public void aPatternMatchingNothingHasNoRoot() {
		assertTrue(
			CollectionScope.roots("nothing-like-this-*", library())
				.isEmpty()
		);
	}

	@Test
	public void resolvingSaysWhatIsWrongRatherThanFailingBlankly() {
		try {
			CollectionScope.requireSingleRoot("nothing-like-this-*", library());
			fail("a pattern matching nothing should be refused");
		} catch (IllegalStateException e) {
			assertTrue(
				e.getMessage(), e.getMessage()
					.contains("nothing-like-this-*")
			);
		}

		var two = new java.util.ArrayList<>(library());
		two.add(new ZoteroCollection("OTHERTOP", "api-other", null));
		try {
			CollectionScope.requireSingleRoot("api-*", two);
			fail("an ambiguous pattern should be refused");
		} catch (IllegalStateException e) {
			// naming the candidates is the difference between a fixable message and
			// a puzzle
			assertTrue(e.getMessage(), e.getMessage()
				.contains("api-test"));
			assertTrue(e.getMessage(), e.getMessage()
				.contains("api-other"));
		}

		assertEquals(
			"TB35FFX6",
			CollectionScope.requireSingleRoot("api-*", library())
				.getKey()
		);
	}

}
