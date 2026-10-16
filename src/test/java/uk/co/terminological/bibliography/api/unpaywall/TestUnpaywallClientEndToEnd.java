package uk.co.terminological.bibliography.api.unpaywall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_AUTHOR_SURNAMES;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_REPOSITORY;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_TITLE;
import static uk.co.terminological.bibliography.TestPaperFixtures.PREPRINT_DOI;

import java.nio.file.Paths;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.api.BibliographicApis;
import uk.co.terminological.bibliography.api.unpaywall.UnpaywallAuthor;
import uk.co.terminological.bibliography.api.unpaywall.UnpaywallClient;
import uk.co.terminological.bibliography.api.unpaywall.UnpaywallResult;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * End-to-end retrieval and parsing test for {@link UnpaywallClient}.
 * <p>
 * Tests retrieving the preprint (DOI {@value uk.co.terminological.bibliography.TestPaperFixtures#PREPRINT_DOI}) as
 * recorded in the local Zotero library (item key {@code J2S7DC97}). Unpaywall indexes it as green open access under
 * journal {@value uk.co.terminological.bibliography.TestPaperFixtures#EXPECTED_REPOSITORY}. Metadata fields parsed by
 * UnpaywallClient are verified against the values recorded in the local Zotero library.
 * <p>
 * Note: for this record Unpaywall's {@code z_authors} entries carry only {@code raw_author_name} (no
 * {@code given}/{@code family}), and {@link UnpaywallAuthor} exposes neither through the {@code Author} interface. So
 * only the author <em>count</em> is asserted, not the names.
 */
@Category(LiveTest.class)
public class TestUnpaywallClientEndToEnd {

	private static UnpaywallClient unpaywall;

	@BeforeClass
	public static void setUp() throws Exception {
		var apis = BibliographicApis.create(
			Paths.get(
				System.getProperty("user.home") + "/.private/apis/secrets.prop"
			),
			Paths.get("/tmp/ehcache/unpaywall-e2e-test")
		);
		unpaywall = apis.getUnpaywall();
	}

	@Test
	public void testRetrieveAndParsePreprintByDoi() {
		RecordReference ref = Builder.recordReference(IdType.DOI, PREPRINT_DOI);
		var fetched = unpaywall.fetch(List.of(ref));

		assertEquals(
			"Expected 1 fetched record for preprint DOI " + PREPRINT_DOI,
			1,
			fetched.size()
		);

		var result = (UnpaywallResult) fetched.values()
			.iterator()
			.next();
		assertNotNull("Fetched UnpaywallResult should not be null", result);
		this.assertUnpaywallResultMatchesZoteroRecord(result);
	}

	/**
	 * Validates title, DOI, repository, and author count against the Zotero record details. Per-author names are not
	 * asserted: Unpaywall's {@code z_authors} for this record carry only {@code raw_author_name}, which
	 * {@link UnpaywallAuthor} does not expose.
	 */
	private void assertUnpaywallResultMatchesZoteroRecord(
			UnpaywallResult result
	) {
		// Title matches
		var title = result.getTitle()
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
			result.getIdentifier()
				.orElse(null)
		);

		// Repository is medRxiv
		assertEquals(
			"Repository/journal should be medRxiv",
			EXPECTED_REPOSITORY,
			result.getJournal()
				.orElse(null)
		);

		// Author count is 16 (names not exposed by Unpaywall for this record)
		assertEquals(
			"Should parse all 16 authors",
			EXPECTED_AUTHOR_SURNAMES.length,
			result.getAuthors()
				.size()
		);

		var authors = result.getAuthors();
		for (var i = 0; i < EXPECTED_AUTHOR_SURNAMES.length; i++) {
			assertEquals(
				"Author " + i + " surname should match Zotero",
				EXPECTED_AUTHOR_SURNAMES[i],
				authors.get(i)
					.getLastName()
			);
		}
	}
}
