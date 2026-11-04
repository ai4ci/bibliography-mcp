package uk.co.terminological.bibliography.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import uk.co.terminological.bibliography.CiteProcProvider;

/**
 * A reference-list entry with no identifier can still be rendered.
 *
 * Roughly half of what the citation APIs return is a reference like "Smith J et al. Lancet 2011;377:1234-42": a real
 * paper, named perfectly well, with no identifier to fetch. {@link CitationReference} exists so such entries are not
 * discarded at parse time, and it carries the print coordinates as the fallback description — but those coordinates
 * were written and never read, so the one thing a citation processor needed to render them never arrived (debt-011).
 *
 * This is the wiring: the coordinates reach CSL, the render works, and the rendered string carries what the entry
 * actually said rather than a guess.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestUnidentifiedReferenceRendering {

	private static CitationReference anUnidentifiedEntry() {
		return ImmutableCitationReference.builder()
			.bibliographicId(
					ImmutablePrintReference.builder()
						.firstAuthorName("Smith")
						.journal("Lancet")
						.volume("377")
						.issue("9775")
						.year(2011L)
						.page("1234-42")
						.build()
			)
			.build();
	}

	@Test
	public void theCoordinatesReachTheCitationProcessor() {
		var entry = anUnidentifiedEntry();

		// the wiring under test: fromCitationReference maps the coordinates into CSL
		var csl = CiteProcProvider.fromCitationReference(entry, "0");

		assertEquals("Lancet", csl.getContainerTitle());
		assertTrue(
				"the year should be the issued year",
				csl.getIssued() != null && csl.getIssued()
					.getDateParts() != null
						&& csl.getIssued()
							.getDateParts().length > 0
						&& csl.getIssued()
							.getDateParts()[0].length > 0
						&& csl.getIssued()
							.getDateParts()[0][0] == 2011
		);
		assertEquals("1234-42", csl.getPage());
		assertEquals("377", csl.getVolume());
		assertEquals("9775", csl.getIssue());
		assertTrue(
				"the first author surname", csl.getAuthor() != null
						&& csl.getAuthor().length > 0
						&& "Smith".equals(csl.getAuthor()[0].getFamily())
		);
	}

	@Test
	public void anEntryWithNoIdentifierAndNoTitleRenders() {
		// the case the debt describes: this entry used to produce an empty render,
		// or a wrong one, because the coordinates never got there
		var csl = CiteProcProvider.fromCitationReference(anUnidentifiedEntry(), "0");

		var rendered = CiteProcProvider
			.render(java.util.List.of(csl), "ieee");

		assertTrue(
				"a nameless, idless entry should still render from its coordinates",
				rendered.isPresent()
		);
		assertTrue(
				"the rendered citation should name the journal: " + rendered.orElse(""),
				rendered.orElse("")
					.contains("Lancet")
		);
		assertTrue(
				rendered.orElse(""),
				rendered.orElse("")
					.contains("Smith")
		);
		assertTrue(
				rendered.orElse(""),
				rendered.orElse("")
					.contains("2011")
		);
	}

	@Test
	public void anEntryWithNothingAtAllStillDoesNotBreakTheBibliography() {
		// the guard: a bare entry must not make the whole bibliography fail
		var bare = ImmutableCitationReference.builder()
			.build();

		var rendered = CiteProcProvider.render(
				java.util.List.of(CiteProcProvider.fromCitationReference(bare, "0")),
				"ieee"
		);

		// either it renders as something empty-ish or it is skipped; what must not
		// happen is an exception taking the bibliography down
		assertTrue(rendered.isEmpty() || rendered.isPresent());
	}

}
