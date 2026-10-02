package uk.co.terminological.bibliography.entrez;

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

import uk.co.terminological.bibliography.BibliographicApis;
import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * End-to-end retrieval and parsing test for {@link EntrezClient}.
 * <p>
 * Tests retrieving the published journal paper corresponding to preprint DOI
 * {@value uk.co.terminological.bibliography.TestPaperFixtures#PREPRINT_DOI} (Zotero item key {@code J2S7DC97}).
 * The published paper in PubMed has published DOI {@value uk.co.terminological.bibliography.TestPaperFixtures#PUBLISHED_DOI}
 * and PMID {@value uk.co.terminological.bibliography.TestPaperFixtures#PMID}. Metadata fields parsed by
 * EntrezClient are verified against the values recorded in the local Zotero library.
 */
@Category(LiveTest.class)
public class TestEntrezClientEndToEnd {

	private static EntrezClient entrez;

	@BeforeClass
	public static void setUp() throws Exception {
		BibliographicApis apis = BibliographicApis.create(
				Paths.get(System.getProperty("user.home") + "/.private/apis/secrets.prop"),
				Paths.get("/tmp/ehcache/entrez-e2e-test")
		);
		entrez = apis.getEntrez();
	}

	@Test
	public void testRetrieveAndParseByPmid() {
		Optional<EntrezEntry> entryOpt = entrez.getPMEntryByPMId(PMID);
		assertTrue("Expected EntrezEntry for PMID " + PMID, entryOpt.isPresent());

		EntrezEntry entry = entryOpt.get();
		assertEntrezEntryMatchesZoteroRecord(entry);
	}

	@Test
	public void testRetrieveAndParseByDoiRecordReference() {
		RecordReference ref = Builder.recordReference(IdType.DOI, PUBLISHED_DOI);
		var fetched = entrez.fetch(List.of(ref));

		assertEquals("Expected 1 fetched record for DOI " + PUBLISHED_DOI, 1, fetched.size());

		EntrezEntry entry = fetched.values().iterator().next();
		assertNotNull("Fetched EntrezEntry should not be null", entry);
		assertEntrezEntryMatchesZoteroRecord(entry);
	}

	/**
	 * Validates title, identifier, DOI, journal, and all 16 authors against
	 * the Zotero record details.
	 */
	private void assertEntrezEntryMatchesZoteroRecord(EntrezEntry entry) {
		// Title matches (ignoring trailing period that PubMed appends to article titles)
		String title = entry.getTitle().orElse("");
		if (title.endsWith(".")) {
			title = title.substring(0, title.length() - 1).trim();
		}
		assertEquals("Title should match Zotero record", EXPECTED_TITLE, title);

		// Identifier is the PMID
		assertEquals("PMID identifier should match", PMID, entry.getIdentifier().orElse(null));
		assertEquals("IdentifierType should be PMID", IdType.PMID, entry.getIdentifierType());

		// Published DOI matches
		assertEquals("DOI in other identifiers should match published DOI",
				PUBLISHED_DOI, entry.getDoi().orElse(null));

		// Journal matches
		assertEquals("Journal should match", EXPECTED_JOURNAL_ABBR, entry.getJournal().orElse(null));

		// Authors match all 16 authors from Zotero in order
		List<? extends Author> authors = entry.getAuthors();
		assertEquals("Should parse all 16 authors", EXPECTED_AUTHOR_SURNAMES.length, authors.size());

		for (int i = 0; i < EXPECTED_AUTHOR_SURNAMES.length; i++) {
			Author author = authors.get(i);
			assertEquals("Author " + i + " surname should match Zotero",
					EXPECTED_AUTHOR_SURNAMES[i], author.getLastName());
			assertTrue("Author " + i + " first name should start with or match Zotero given name",
					author.getFirstName().orElse("")
							.replaceAll("[\\.\\s]", "")
							.equalsIgnoreCase(EXPECTED_AUTHOR_FIRST_NAMES[i].replaceAll("[\\.\\s]", "")));
		}
	}
}
