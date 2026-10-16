package uk.co.terminological.bibliography.api.crossref;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_AUTHOR_FIRST_NAMES;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_AUTHOR_SURNAMES;
import static uk.co.terminological.bibliography.TestPaperFixtures.EXPECTED_TITLE;
import static uk.co.terminological.bibliography.TestPaperFixtures.PUBLISHED_DOI;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.terminological.bibliography.api.crossref.CrossRefListResult;
import uk.co.terminological.bibliography.api.crossref.CrossRefSingleResult;
import uk.co.terminological.bibliography.api.crossref.CrossRefWork;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;

/**
 * Tier-1 extraction tests (plan-007): recorded CrossRef responses are fed through the same parse classes the client
 * uses, and every main output of the coverage matrix is asserted against the canonical corpus. Offline, deterministic,
 * in the default build.
 */
public class TestCrossRefExtraction {

	static CrossRefWork published;
	static CrossRefWork preprint;
	static CrossRefWork cited;

	@BeforeClass
	public static void parseTheRecordedCorpus() throws Exception {
		published = work("fixtures/crossref/work-published.json");
		preprint = work("fixtures/crossref/work-preprint.json");
		cited = work("fixtures/crossref/work-cited.json");
	}

	static CrossRefWork work(String fixture) throws Exception {
		try (var is = ClassLoader.getSystemResourceAsStream(fixture)) {
			return new CrossRefSingleResult(new ObjectMapper().readTree(is))
				.getWork();
		}
	}

	@Test
	public void aJournalArticleReadsThroughTheWholeRecordInterface() {
		// CrossRef's own record keeps a Unicode en dash in the title's date range where
		// EuropePMC serves an ASCII hyphen: extraction must stay faithful to its source,
		// so the dash as CrossRef writes it is pinned here (cross-source title
		// normalisation, if ever wanted, is a merge-layer decision).
		assertEquals(
			EXPECTED_TITLE.replace("2006-2022", "2006\u20132022"),
			published.getTitle()
				.orElse("<no title>")
		);
		// CrossRef's "journal-article" is normalised into the project's CSL vocabulary
		assertEquals(
			"article-journal",
			published.getPublicationType()
				.orElse("<none>")
		);
		// CrossRef records the legal registrant name, not the "Springer Nature" imprint
		assertEquals(
			"Springer Science and Business Media LLC",
			published.getPublisher()
				.orElse("<none>")
		);
		// CrossRef title-cases the container title where EuropePMC sentence-cases it;
		// each API is pinned to its own source, case-folding is a merge-layer concern
		assertEquals(
			"BMC Infectious Diseases",
			published.getJournal()
				.orElse("<none>")
		);
		assertEquals(
			PUBLISHED_DOI,
			published.getIdentifier()
				.orElse("<none>")
		);
		// NOTE: CrossRefWork.getOtherIdentifiers() is hardcoded empty, so CrossRef's
		// own identifier[]/alternative-id arrays (PMID etc.) never surface — pinned as
		// a known model limitation rather than fixed (see plan-007 findings).
		assertTrue(
			"no other ids surface from CrossRefWork today",
			published.getOtherIdentifiers()
				.isEmpty()
		);
		assertTrue(
			"a date is expected",
			published.getDate()
				.isPresent()
		);
	}

	@Test
	public void allSixteenAuthorsKeepBothNames() {
		List<? extends Author> authors = published.getAuthors();
		assertEquals(EXPECTED_AUTHOR_SURNAMES.length, authors.size());
		for (var i = 0; i != authors.size(); i++) {
			assertEquals(
				"surname " + i,
				EXPECTED_AUTHOR_SURNAMES[i],
				authors.get(i)
					.getLastName()
			);
			// CrossRef keeps trailing dots on initials ("Nick A.") where EuropePMC drops
			// them: presence is an extraction guarantee, orthography is a merge concern
			assertEquals(
				"first name " + i,
				EXPECTED_AUTHOR_FIRST_NAMES[i].replace(".", ""),
				authors.get(i)
					.getFirstName()
					.orElse("<none>")
					.replace(".", "")
					.trim()
			);
		}
	}

	@Test
	public void aPreprintIsPostedContentOwnedByItsServerWithNoJournal() {
		assertEquals(
			"posted-content",
			preprint.getPublicationType()
				.orElse("<none>")
		);
		assertEquals(
			"openRxiv",
			preprint.getPublisher()
				.orElse("<none>")
		);
		// the recorded title carries <i> markup and newlines: PlainText must have
		// normalised it into the same string the other APIs yield
		assertEquals(
			EXPECTED_TITLE,
			preprint.getTitle()
				.orElse("<no title>")
		);
		var journal = preprint.getJournal()
			.orElse("");
		assertTrue(
			"a preprint has no journal, got: " + journal,
			journal.trim()
				.isEmpty()
		);
	}

	@Test
	public void everyRecordedReferenceBecomesACitationNamedByIdentifierOrTitle() {
		var links = cited.getCitations();
		assertTrue(
			"expected a citation-rich work, got " + links.size(),
			links.size() >= 20
		);
		for (CitationLink link : links) {
			assertTrue(
				"citation " + link.getIndex()
					.orElse(-1) + " has neither an identifier nor a title",
				link.getTarget()
					.getIdentifier()
					.isPresent()
						|| link.getTarget()
							.getTitle()
							.isPresent()
			);
		}
		assertTrue(
			"at least one reference should carry its DOI",
			links.stream()
				.anyMatch(
					l -> l.getTarget()
						.getIdentifier()
						.map(
							r -> r.getIdentifierType()
								.equals(IdType.DOI)
						)
						.orElse(false)
				)
		);
	}

	@Test
	public void aSearchResponseListParsesIntoWorkRecords() throws Exception {
		// the legacy ontologiesExample.json main() harness, converted to assertions;
		// it is also the only test of the CrossRef *list* (search) parse seam
		CrossRefListResult list;
		try (var is = ClassLoader
			.getSystemResourceAsStream("ontologiesExample.json")) {
			list = new CrossRefListResult(new ObjectMapper().readTree(is));
		}
		List<CrossRefWork> items = list.getMessage()
			.getItems()
			.collect(Collectors.toList());
		assertEquals("a default CrossRef search page", 20, items.size());
		var first = items.get(0);
		assertEquals(
			"Strategic Reading, Ontologies, and the Future of Scientific Publishing",
			first.getTitle()
				.orElse("<no title>")
		);
		assertEquals(
			"10.1126/science.1157784",
			first.getIdentifier()
				.orElse("<none>")
		);
		assertEquals(
			"Science",
			first.getJournal()
				.orElse("<none>")
		);
		assertEquals(
			"325",
			first.getVolume()
				.orElse("<none>")
		);
		assertEquals(
			"5942",
			first.getIssue()
				.orElse("<none>")
		);
		assertEquals(
			"828-832",
			first.getPage()
				.orElse("<none>")
		);
		assertEquals(
			LocalDate.of(2009, 8, 13),
			first.getDate()
				.orElseThrow()
		);
		assertEquals(
			66L,
			first.getCitedByCount()
				.orElse(-1L)
				.longValue()
		);
		assertEquals(
			28L,
			first.getReferencesCount()
				.orElse(-1L)
				.longValue()
		);
	}
}
