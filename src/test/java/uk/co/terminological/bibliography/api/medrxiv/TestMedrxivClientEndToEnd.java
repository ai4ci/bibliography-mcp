package uk.co.terminological.bibliography.api.medrxiv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static uk.co.terminological.bibliography.TestPaperFixtures.*;

import java.nio.file.Paths;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.api.BibliographicApis;
import uk.co.terminological.bibliography.api.medrxiv.MedrxivClient;
import uk.co.terminological.bibliography.api.medrxiv.MedrxivRecord;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * End-to-end retrieval and parsing test for {@link MedrxivClient}.
 * <p>
 * Tests retrieving the preprint (DOI {@value uk.co.terminological.bibliography.TestPaperFixtures#PREPRINT_DOI}) as
 * recorded in the local Zotero library (item key {@code J2S7DC97}).
 * <p>
 * <b>Known gap this test pins:</b> this preprint is posted to openRxiv/medRxiv under the {@code 10.64898/} DOI prefix,
 * but {@link MedrxivClient#fetch} only looks at DOIs under the {@code 10.1101/} prefix (see
 * {@code MedrxivClient.isPreprintDoi}). The biorxiv API can and does serve this DOI, so the fetch below is expected to
 * be <em>empty</em> until the prefix gate is widened. This test therefore fails today and documents that gap.
 * <p>
 * medRxiv parses authors as surname + initials only (from the {@code "Challen, R.; ..."} string), so per-author given
 * names are not asserted — only surnames and the count.
 */
@Category(LiveTest.class)
public class TestMedrxivClientEndToEnd {

	private static MedrxivClient medrxiv;

	@BeforeClass
	public static void setUp() throws Exception {
		BibliographicApis apis = BibliographicApis.create(
			Paths.get(
				System.getProperty("user.home") + "/.private/apis/secrets.prop"
			),
			Paths.get("/tmp/ehcache/medrxiv-e2e-test")
		);
		medrxiv = apis.getMedrxiv();
	}

	@Test
	public void testRetrieveAndParsePreprintByDoi() {
		RecordReference ref = Builder.recordReference(IdType.DOI, PREPRINT_DOI);
		var fetched = medrxiv.fetch(List.of(ref));

		// Expected to be empty today: MedrxivClient only fetches 10.1101/ DOIs, but this
		// preprint is 10.64898/. This assertion is the TDD signal for that gap.
		assertEquals(
			"Expected 1 fetched record for preprint DOI " + PREPRINT_DOI
					+ " (currently empty: MedrxivClient only handles 10.1101/ DOIs)",
			1,
			fetched.size()
		);

		MedrxivRecord record = (MedrxivRecord) fetched.values()
			.iterator()
			.next();
		assertNotNull("Fetched MedrxivRecord should not be null", record);
		assertMedrxivRecordMatchesZoteroRecord(record);
	}

	/**
	 * Validates title, DOI, and all 16 author surnames against the Zotero record details. Given names are not asserted:
	 * medRxiv exposes surname + initials only.
	 */
	private void assertMedrxivRecordMatchesZoteroRecord(MedrxivRecord record) {
		// Title matches
		String title = record.getTitle()
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
			record.getIdentifier()
				.orElse(null)
		);
		assertEquals(
			"IdentifierType should be DOI",
			IdType.DOI,
			record.getIdentifierType()
		);

		// Authors match all 16 surnames from Zotero in order (given names unavailable)
		List<? extends Author> authors = record.getAuthors();
		assertEquals(
			"Should parse all 16 authors",
			EXPECTED_AUTHOR_SURNAMES.length,
			authors.size()
		);

		for (int i = 0; i < EXPECTED_AUTHOR_SURNAMES.length; i++) {
			assertEquals(
				"Author " + i + " surname should match Zotero",
				EXPECTED_AUTHOR_SURNAMES[i],
				authors.get(i)
					.getLastName()
			);
		}
	}
}
