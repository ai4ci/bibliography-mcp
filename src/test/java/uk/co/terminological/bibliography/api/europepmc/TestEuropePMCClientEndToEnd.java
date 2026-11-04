package uk.co.terminological.bibliography.api.europepmc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static uk.co.terminological.bibliography.TestPaperFixtures.*;

import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.api.BibliographicApis;
import uk.co.terminological.bibliography.api.europepmc.EuropePMCClient;
import uk.co.terminological.bibliography.api.europepmc.EuropePMCCoreResult;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;

/**
 * End-to-end retrieval and parsing test for {@link EuropePMCClient}.
 * <p>
 * Tests retrieving the published journal paper corresponding to preprint DOI
 * {@value uk.co.terminological.bibliography.TestPaperFixtures#PREPRINT_DOI} (Zotero item key {@code J2S7DC97}). In
 * Europe PMC the published paper carries PMID {@value uk.co.terminological.bibliography.TestPaperFixtures#PMID} under
 * source {@code MED} and PMCID {@value uk.co.terminological.bibliography.TestPaperFixtures#PMCID}. Metadata fields
 * parsed by EuropePMCClient are verified against the values recorded in the local Zotero library.
 */
@Category(LiveTest.class)
public class TestEuropePMCClientEndToEnd {

	private static EuropePMCClient europePMC;

	@BeforeClass
	public static void setUp() throws Exception {
		BibliographicApis apis = BibliographicApis.create(
			Paths.get(
				System.getProperty("user.home") + "/.private/apis/secrets.prop"
			),
			Paths.get("/tmp/ehcache/europepmc-e2e-test")
		);
		europePMC = apis.getEuropePMC();
	}

	@Test
	public void theSamePaperGivesTheSameReferencesByEveryIdentifier() {
		// debt-010 collapsed six near-identical loops into one dispatch. The thing
		// that must survive it is this: Europe PMC is asked about a PMID, a PMCID and
		// a DOI by three different routes — two direct, one through a search that
		// resolves the DOI to EPMC's own source — and the three have to agree.
		var byPmid = europePMC.citesReferences(
			List.of(Builder.recordReference(IdType.PMID, PMID))
		);
		var byPmcid = europePMC.citesReferences(
			List.of(Builder.recordReference(IdType.PMCID, PMCID))
		);
		var byDoi = europePMC.citesReferences(
			List.of(Builder.recordReference(IdType.DOI, PUBLISHED_DOI))
		);

		assertTrue(
			"this paper should have references in Europe PMC", byPmid.size() > 10
		);
		assertEquals(
			"a PMCID should reach the same reference list as a PMID", byPmid.size(),
			byPmcid.size()
		);
		assertEquals(
			"a DOI should reach the same reference list as a PMID — this is the gap"
					+ " that made a preprint's references look absent",
			byPmid.size(),
			byDoi.size()
		);
	}

	@Test
	public void anIdentifierEuropePmcCannotBeAskedAboutCostsNothing() {
		// an ISBN has no Europe PMC source, so it is not a question; before the
		// dispatch was in one place this depended on each loop remembering
		var out = europePMC.citesReferences(
			List.of(Builder.recordReference(IdType.ISBN, "978-0-19-852663-6"))
		);

		assertTrue("an ISBN is not a Europe PMC question", out.isEmpty());
	}

	@Test
	public void testRetrieveAndParseByDoi() {
		Optional<EuropePMCCoreResult> resultOpt = europePMC
			.getById(PUBLISHED_DOI, IdType.DOI);
		assertTrue(
			"Expected EuropePMCCoreResult for DOI " + PUBLISHED_DOI,
			resultOpt.isPresent()
		);

		EuropePMCCoreResult result = resultOpt.get();
		assertCoreResultMatchesZoteroRecord(result);
	}

	@Test
	public void testRetrieveAndParseByPmid() {
		Optional<EuropePMCCoreResult> resultOpt = europePMC
			.getById(PMID, IdType.PMID);
		assertTrue(
			"Expected EuropePMCCoreResult for PMID " + PMID,
			resultOpt.isPresent()
		);

		EuropePMCCoreResult result = resultOpt.get();
		assertCoreResultMatchesZoteroRecord(result);
	}

	@Test
	public void testCoreResultCarriesAllThreeIdentifiers() {
		Optional<EuropePMCCoreResult> resultOpt = europePMC
			.getById(PUBLISHED_DOI, IdType.DOI);
		assertTrue(
			"Expected EuropePMCCoreResult for DOI " + PUBLISHED_DOI,
			resultOpt.isPresent()
		);

		EuropePMCCoreResult result = resultOpt.get();
		assertEquals(
			"DOI should match published DOI",
			PUBLISHED_DOI,
			result.getDOI()
				.orElse(null)
		);
		assertEquals(
			"PMID should match",
			PMID,
			result.getPMID()
				.orElse(null)
		);
		assertEquals(
			"PMCID should match",
			PMCID,
			result.getPMCID()
				.orElse(null)
		);
	}

	/**
	 * Validates title, PMID, and all 16 authors against the Zotero record details. The core response does not carry a
	 * top-level {@code journalTitle}, so the journal name is not asserted here.
	 */
	private void assertCoreResultMatchesZoteroRecord(
			EuropePMCCoreResult result
	) {
		// Title matches (ignoring trailing period that EPMC/PubMed append to article titles)
		String title = result.getTitle()
			.orElse("");
		if (title.endsWith(".")) {
			title = title.substring(0, title.length() - 1)
				.trim();
		}
		assertEquals("Title should match Zotero record", EXPECTED_TITLE, title);

		// PMID present
		assertEquals(
			"PMID should match",
			PMID,
			result.getPMID()
				.orElse(null)
		);

		// Authors match all 16 authors from Zotero in order
		List<? extends Author> authors = result.getAuthors();
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
