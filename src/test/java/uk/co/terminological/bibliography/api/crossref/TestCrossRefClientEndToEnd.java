package uk.co.terminological.bibliography.api.crossref;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static uk.co.terminological.bibliography.TestPaperFixtures.*;

import java.nio.file.Paths;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.api.BibliographicApis;
import uk.co.terminological.bibliography.api.crossref.CrossRefClient;
import uk.co.terminological.bibliography.api.crossref.CrossRefWork;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * End-to-end retrieval and parsing test for {@link CrossRefClient}.
 * <p>
 * Tests retrieving the preprint (DOI {@value uk.co.terminological.bibliography.TestPaperFixtures#PREPRINT_DOI}) as
 * recorded in the local Zotero library (item key {@code J2S7DC97}). CrossRef indexes the preprint as posted content
 * (publisher openRxiv, no journal). Metadata fields parsed by CrossRefClient are verified against the values recorded
 * in the local Zotero library.
 */
@Category(LiveTest.class)
public class TestCrossRefClientEndToEnd {

	/** Publisher CrossRef reports for the openRxiv preprint */
	public static final String EXPECTED_PUBLISHER = "openRxiv";

	private static CrossRefClient crossref;

	@BeforeClass
	public static void setUp() throws Exception {
		BibliographicApis apis = BibliographicApis.create(
			Paths.get(
				System.getProperty("user.home") + "/.private/apis/secrets.prop"
			),
			Paths.get("/tmp/ehcache/crossref-e2e-test")
		);
		crossref = apis.getCrossref();
	}

	@Test
	public void testRetrieveAndParsePreprintByDoi() {
		RecordReference ref = Builder.recordReference(IdType.DOI, PREPRINT_DOI);
		var fetched = crossref.fetch(List.of(ref));

		assertEquals(
			"Expected 1 fetched record for preprint DOI " + PREPRINT_DOI,
			1,
			fetched.size()
		);

		CrossRefWork work = (CrossRefWork) fetched.values()
			.iterator()
			.next();
		assertNotNull("Fetched CrossRefWork should not be null", work);
		assertCrossRefWorkMatchesZoteroRecord(work);
	}

	/**
	 * Validates title, DOI, publisher, and all 16 authors against the Zotero record details. The preprint has no journal on
	 * CrossRef (it is posted content), so the journal is not asserted; the publisher (openRxiv) is asserted instead.
	 */
	private void assertCrossRefWorkMatchesZoteroRecord(CrossRefWork work) {
		// Title matches (PlainText.of strips the <i> markup and normalises whitespace)
		String title = work.getTitle()
			.orElse("");
		if (title.endsWith(".")) {
			title = title.substring(0, title.length() - 1)
				.trim();
		}
		assertEquals("Title should match Zotero record", EXPECTED_TITLE, title);

		// Identifier is the preprint DOI
		assertEquals(
			"DOI should match preprint DOI",
			PREPRINT_DOI,
			work.getIdentifier()
				.orElse(null)
		);
		assertEquals(
			"IdentifierType should be DOI",
			IdType.DOI,
			work.getIdentifierType()
		);

		// Publisher is openRxiv (the preprint service)
		assertEquals(
			"Publisher should be openRxiv",
			EXPECTED_PUBLISHER,
			work.getPublisher()
				.orElse(null)
		);

		// Authors match all 16 authors from Zotero in order
		List<? extends Author> authors = work.getAuthors();
		assertEquals(
			"Should parse all 16 authors",
			EXPECTED_AUTHOR_SURNAMES.length,
			authors.size()
		);

		for (int i = 0; i < EXPECTED_AUTHOR_SURNAMES.length; i++) {
			Author author = authors.get(i);
			assertEquals(
				"Author " + i + " surname should match Zotero",
				EXPECTED_AUTHOR_SURNAMES[i],
				author.getLastName()
			);
			assertTrue(
				"Author " + i
						+ " first name should start with or match Zotero given name",
				author.getFirstName()
					.orElse("")
					.replaceAll("[\\.\\s]", "")
					.equalsIgnoreCase(
						EXPECTED_AUTHOR_FIRST_NAMES[i].replaceAll("[\\.\\s]", "")
					)
			);
		}
	}
}
