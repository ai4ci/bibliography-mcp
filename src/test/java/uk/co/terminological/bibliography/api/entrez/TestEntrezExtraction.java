package uk.co.terminological.bibliography.api.entrez;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_AUTHOR_SURNAMES;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_JOURNAL_ABBR;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_TITLE;
import static uk.co.terminological.bibliography.TestPaperFixtures.PMCID;
import static uk.co.terminological.bibliography.TestPaperFixtures.PMID;
import static uk.co.terminological.bibliography.TestPaperFixtures.PUBLISHED_DOI;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.BeforeClass;
import org.junit.Test;

import uk.co.terminological.fluentxml.Xml;

/**
 * Tier-1 extraction tests (plan-007): recorded eutils responses (captured by FixtureRecorder with the same
 * db/term/id/linkname parameters the client builds) fed through the client's own parse classes. This replaces the old
 * assertion-free TestEntrezParsing main() harness, whose legacy fixture is asserted too.
 */
public class TestEntrezExtraction {

	static EntrezSearch search;
	static EntrezEntries entries;
	static EntrezEntry canonical;
	static EntrezLinks refs;

	@BeforeClass
	public static void parseTheRecordedCorpus() throws Exception {
		search = new EntrezSearch(xml("fixtures/entrez/esearch-doi.xml"));
		entries = new EntrezEntries(xml("fixtures/entrez/efetch-pmid.xml"));
		canonical = entries.stream()
			.findFirst()
			.orElseThrow(
				() -> new IllegalStateException("no record in efetch fixture")
			);
		refs = new EntrezLinks(xml("fixtures/entrez/elink-refs.xml"));
	}

	static uk.co.terminological.fluentxml.XmlElement xml(String fixture)
			throws Exception {
		try (var is = ClassLoader.getSystemResourceAsStream(fixture)) {
			return Xml.fromStream(is)
				.content();
		}
	}

	@Test
	public void anEsearchByDoiFindsTheCanonicalPmid() {
		assertTrue(
			"the search should report a hit",
			search.count()
				.orElse(0) >= 1
		);
		List<String> ids = search.getIds()
			.collect(Collectors.toList());
		assertTrue("the canonical PMID should be in " + ids, ids.contains(PMID));
	}

	@Test
	public void theCanonicalRecordReadsThroughTheWholeRecordInterface() {
		// PubMed, like EuropePMC, appends a trailing period to the title
		assertEquals(
			EXPECTED_TITLE + ".",
			canonical.getTitle()
				.orElse("<no title>")
		);
		assertEquals(
			PMID,
			canonical.getIdentifier()
				.orElse("<none>")
		);
		assertEquals(
			uk.co.terminological.bibliography.record.IdType.PMID,
			canonical.getIdentifierType()
		);
		assertEquals(
			PUBLISHED_DOI,
			canonical.getDoi()
				.orElse("<none>")
		);
		assertTrue(
			"the PMCID should surface",
			canonical.getPMCID()
				.orElse("<none>")
				.contains(PMCID)
		);
		// Entrez reads the journal as its ISO abbreviation
		assertEquals(
			EXPECTED_JOURNAL_ABBR,
			canonical.getJournal()
				.orElse("<none>")
		);
		assertEquals(
			"26",
			canonical.getVolume()
				.orElse("<none>")
		);
		assertEquals(
			"1",
			canonical.getIssue()
				.orElse("<none>")
		);
		assertEquals(
			LocalDate.of(2026, 3, 26),
			canonical.getDate()
				.orElseThrow()
		);
		assertTrue(
			"an abstract is expected",
			canonical.getAbstract()
				.orElse("<none>")
				.length() > 200
		);
	}

	@Test
	public void theRecordDateIsWhenTheWorkWasPublished() {
		// Still failing, and narrowed. getDate() now prefers Article/ArticleDate,
		// which is the electronic publication date and the one PubMed's own pdat
		// filter matches. This record has no ArticleDate, so it falls back to
		// PubMedPubDate[@PubStatus='pubmed'] -- when the record entered PubMed --
		// and reports 2026-03-27 where the issue date is 2026-03-26.
		//
		// The remaining gap is the middle fallback: Article/Journal/JournalIssue/
		// PubDate, deliberately not implemented because it carries partial
		// precision (a bare year, "2019 Jun", or a MedlineDate like
		// "2019 Jun-Jul") that Optional<LocalDate> cannot represent. See
		// issue-014: the fix needs a decision about partial dates, not just a
		// parser.
		assertEquals(
			LocalDate.of(2026, 3, 26),
			canonical.getDate()
				.orElse(null)
		);
	}

	@Test
	public void allSixteenAuthorsSurviveWithNamesAndInitials() {
		var authors = canonical.getAuthors();
		assertEquals(EXPECTED_AUTHOR_SURNAMES.length, authors.size());
		for (var i = 0; i != authors.size(); i++) {
			assertEquals(
				"surname " + i,
				EXPECTED_AUTHOR_SURNAMES[i],
				authors.get(i)
					.getLastName()
			);
			assertTrue(
				"author " + i + " should have initials",
				authors.get(i)
					.getInitials()
					.map(s -> !s.isEmpty())
					.orElse(false)
			);
		}
	}

	@Test
	public void elinkReferenceRowsPointAtOtherPubMedRecords() throws Exception {
		List<EntrezLink> links = refs.stream()
			.collect(Collectors.toList());
		assertTrue(
			"the canonical article should have reference links",
			links.size() > 0
		);
		for (EntrezLink link : links) {
			assertEquals(
				"every row should start from the canonical PMID",
				PMID,
				link.fromId
			);
			// pubmed_pubmed_refs resolves references to other PubMed records by PMID
			assertTrue(
				"reference ids should be PMIDs, got " + link.toId,
				link.toId.map(s -> s.matches("\\d+"))
					.orElse(false)
			);
		}
	}

	@Test
	public void theLegacyCryobiologyRecordStillParses() throws Exception {
		// formerly the assertion-free TestEntrezParsing harness; its values are now
		// pinned: a 2001 print-only record with initials-only authors and page ranges
		var legacy = new EntrezEntries(xmlAt("efetch.xml")).stream()
			.findFirst()
			.orElseThrow();
		assertEquals(
			"Is cryopreservation a homogeneous process? Ultrastructure and motility of "
					+ "untreated, prefreezing, and postthawed spermatozoa of Diplodus puntazzo (Cetti).",
			legacy.getTitle()
				.orElse("<no title>")
		);
		assertEquals(
			"11748933",
			legacy.getIdentifier()
				.orElse("<none>")
		);
		assertEquals(
			"Cryobiology",
			legacy.getJournal()
				.orElse("<none>")
		);
		assertEquals(
			LocalDate.of(2001, 12, 26),
			legacy.getDate()
				.orElseThrow()
		);
		assertEquals(
			"42",
			legacy.getVolume()
				.orElse("<none>")
		);
		assertEquals(
			"4",
			legacy.getIssue()
				.orElse("<none>")
		);
		assertEquals(
			"244-55",
			legacy.getPage()
				.orElse("<none>")
		);
		assertEquals(
			"Taddei",
			legacy.getAuthors()
				.get(0)
				.getLastName()
		);
		assertEquals(
			"A R",
			legacy.getAuthors()
				.get(0)
				.getFirstName()
				.orElse("<none>")
		);
	}

	static uk.co.terminological.fluentxml.XmlElement xmlAt(String resource)
			throws Exception {
		try (var is = ClassLoader.getSystemResourceAsStream(resource)) {
			return Xml.fromStream(is)
				.content();
		}
	}
}
