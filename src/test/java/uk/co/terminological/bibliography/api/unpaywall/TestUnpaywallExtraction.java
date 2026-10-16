package uk.co.terminological.bibliography.api.unpaywall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_AUTHOR_SURNAMES;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_REPOSITORY;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_TITLE;

import java.time.LocalDate;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.terminological.bibliography.api.unpaywall.UnpaywallResult;

/**
 * Tier-1 extraction tests (plan-007): recorded Unpaywall responses parsed through UnpaywallResult itself.
 */
public class TestUnpaywallExtraction {

	static UnpaywallResult published;
	static UnpaywallResult preprint;

	@BeforeClass
	public static void parseTheRecordedCorpus() throws Exception {
		published = result("fixtures/unpaywall/oa-published.json");
		preprint = result("fixtures/unpaywall/preprint.json");
	}

	static UnpaywallResult result(String fixture) throws Exception {
		try (var is = ClassLoader.getSystemResourceAsStream(fixture)) {
			return new UnpaywallResult(new ObjectMapper().readTree(is));
		}
	}

	@Test
	public void thePublishedArticleKeepsItsCoreMetadata() {
		// the registered (CrossRef-sourced) record carries the Unicode en dash
		assertEquals(
			EXPECTED_TITLE.replace("2006-2022", "2006\u20132022"),
			published.getTitle()
				.orElse("<no title>")
		);
		assertEquals(
			"BMC Infectious Diseases",
			published.getJournal()
				.orElse("<none>")
		);
		assertEquals(
			"Springer Science and Business Media LLC",
			published.getRaw()
				.path("publisher")
				.asText("<none>")
		);
		assertTrue(
			"is_oa must surface",
			published.getRaw()
				.path("is_oa")
				.asBoolean(false)
		);
		assertEquals(
			LocalDate.of(2026, 3, 26),
			published.getDate()
				.orElseThrow()
		);
		assertEquals(
			16,
			published.getAuthors()
				.size()
		);
	}

	@Test
	public void rawAuthorNamesAreUsedWhenStructuredNamesAreAbsent() {
		// this record has given/family set to null for every author; the raw
		// "Robert Challen" string is the only thing a user of this API could get
		var authors = published.getAuthors();
		assertEquals(
			"Challen",
			authors.get(0)
				.getLastName()
		);
		assertEquals(
			"Robert",
			authors.get(0)
				.getFirstName()
				.orElse("<none>")
		);
		for (var i = 0; i != authors.size(); i++) {
			assertEquals(
				"surname " + i,
				EXPECTED_AUTHOR_SURNAMES[i],
				authors.get(i)
					.getLastName()
			);
		}
	}

	@Test
	public void thePreprintIsOwnedByItsRepository() {
		assertEquals(
			EXPECTED_TITLE,
			preprint.getTitle()
				.orElse("<no title>")
		);
		assertEquals(
			EXPECTED_REPOSITORY,
			preprint.getJournal()
				.orElse("<none>")
		);
		assertEquals(
			"openRxiv",
			preprint.getRaw()
				.path("publisher")
				.asText("<none>")
		);
		assertEquals(
			LocalDate.of(2025, 12, 15),
			preprint.getDate()
				.orElseThrow()
		);
		assertEquals(
			16,
			preprint.getAuthors()
				.size()
		);
		// the best full text of the preprint is served by the repository itself
		var best = preprint.getRaw()
			.path("best_oa_location");
		assertEquals(
			"repository",
			best.path("host_type")
				.asText("<none>")
		);
	}

	@Test
	public void theLegacyOpenAccessRecordStillParses() throws Exception {
		// the legacy unpaywall.json main() harness, converted to assertions
		var legacy = result("unpaywall.json");
		assertEquals(
			"Nanometre-scale thermometry in a living cell",
			legacy.getTitle()
				.orElse("<no title>")
		);
		assertEquals(
			"10.1038/nature12373",
			legacy.getIdentifier()
				.orElse("<none>")
		);
		assertEquals(
			"Nature",
			legacy.getJournal()
				.orElse("<none>")
		);
		assertEquals(
			LocalDate.of(2013, 7, 31),
			legacy.getDate()
				.orElseThrow()
		);
		assertEquals(
			"Kucsko",
			legacy.getAuthors()
				.get(0)
				.getLastName()
		);
		assertEquals(
			"G.",
			legacy.getAuthors()
				.get(0)
				.getFirstName()
				.orElse("<none>")
		);
		assertTrue(
			"the best OA pdf should surface",
			legacy.getPdfUri()
				.map(
					u -> u.toString()
						.contains("dash.harvard.edu")
				)
				.orElse(false)
		);
	}
}
