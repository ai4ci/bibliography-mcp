package uk.co.terminological.bibliography.mcp;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.Test;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableCitationReference;

/**
 * The same citation arriving from two APIs is one citation: with a preprint's references coming from both Europe PMC
 * and its own JATS, counts would otherwise be inflated.
 *
 * [TESTS](/design/test-scripts/test-005-preprint-tests.md)
 */
public class TestCitationDedupe {

	private static CitationLink link(
			String sourceDoi, String targetDoi, String targetTitle
	) {
		var source = ImmutableCitationReference.builder()
			.identifier(Builder.recordReference(IdType.DOI, sourceDoi))
			.build();
		var target = ImmutableCitationReference.builder()
			.identifier(
				Optional.ofNullable(
					targetDoi == null ? null
						: Builder.recordReference(IdType.DOI, targetDoi)
				)
			)
			.title(Optional.ofNullable(targetTitle))
			.build();
		return Builder.citationLink(source, target, null);
	}

	@Test
	public void theSamePairFromTwoApisCollapsesToOne() {
		var deduped = BiblioFacade.dedupeCitations(
			List.of(
				link("10.1/preprint", "10.1136/a", "A"),
				link("10.1/preprint", "10.1136/a", "A")
			)
		);

		assertEquals(1, deduped.size());
	}

	@Test
	public void differentTargetsAreKeptApart() {
		var deduped = BiblioFacade.dedupeCitations(
			List.of(
				link("10.1/preprint", "10.1136/a", "A"),
				link("10.1/preprint", "10.1136/b", "B")
			)
		);

		assertEquals(2, deduped.size());
	}

	@Test
	public void referencesWithoutAnIdentifierAreNotAllTreatedAsTheSameOne() {
		// a JATS reference may have no DOI, and its title is then what distinguishes it
		var deduped = BiblioFacade.dedupeCitations(
			List.of(
				link("10.1/preprint", null, "A paper without a doi"),
				link("10.1/preprint", null, "Another paper without a doi"),
				link("10.1/preprint", null, "A paper without a doi")
			)
		);

		assertEquals(2, deduped.size());
	}

	@Test
	public void theRicherDescriptionOfTheSameCitationIsTheOneKept() {
		// Europe PMC may describe a citation with no title while the preprint's own
		// JATS describes the same one fully; whichever came first must not decide
		var thin = link("10.1/preprint", "10.1136/a", null);
		var rich = link("10.1/preprint", "10.1136/a", "A title");

		var deduped = BiblioFacade.dedupeCitations(List.of(thin, rich));

		assertEquals(1, deduped.size());
		assertEquals(
			"A title",
			deduped.iterator()
				.next()
				.getTarget()
				.getTitle()
				.orElse(null)
		);
	}

	@Test
	public void linksWithNeitherIdentifierNorTitleAreKeptRatherThanCollapsed() {
		// this bit in a live run: an "unknown" dedupe key merged every unidentified
		// reference into one, turning 31 references into 17
		var source = ImmutableCitationReference.builder()
			.identifier(Builder.recordReference(IdType.DOI, "10.1/preprint"))
			.build();
		var blank = ImmutableCitationReference.builder()
			.build();
		List<CitationLink> links = List.of(
			Builder.citationLink(source, blank, 1),
			Builder.citationLink(source, blank, 2),
			Builder.citationLink(source, blank, 3)
		);

		assertEquals(
			3,
			BiblioFacade.dedupeCitations(links)
				.size()
		);
	}

}
