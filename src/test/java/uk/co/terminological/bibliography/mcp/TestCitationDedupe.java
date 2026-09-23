package uk.co.terminological.bibliography.mcp;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.Test;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.CitationReference;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableCitationLink;

/**
 * The same citation arriving from two APIs is one citation: with a preprint's
 * references coming from both Europe PMC and its own JATS, counts would otherwise
 * be inflated.
 *
 * [TESTS](/design/test-scripts/test-005-preprint-tests.md)
 */
public class TestCitationDedupe {

	private static CitationLink link(String sourceDoi, String targetDoi, String targetTitle) {
		CitationReference source = CitationReference.create(
				Builder.recordReference(IdType.DOI, sourceDoi), null, null);
		CitationReference target = CitationReference.create(
				targetDoi == null ? null : Builder.recordReference(IdType.DOI, targetDoi), targetTitle, null);
		return new ImmutableCitationLink(source, target, Optional.empty());
	}

	@Test
	public void theSamePairFromTwoApisCollapsesToOne() {
		var deduped = BiblioFacade.dedupeCitations(List.of(
				link("10.1/preprint", "10.1136/a", "A"),
				link("10.1/preprint", "10.1136/a", "A")));

		assertEquals(1, deduped.size());
	}

	@Test
	public void differentTargetsAreKeptApart() {
		var deduped = BiblioFacade.dedupeCitations(List.of(
				link("10.1/preprint", "10.1136/a", "A"),
				link("10.1/preprint", "10.1136/b", "B")));

		assertEquals(2, deduped.size());
	}

	@Test
	public void referencesWithoutAnIdentifierAreNotAllTreatedAsTheSameOne() {
		// a JATS reference may have no DOI, and its title is then what distinguishes it
		var deduped = BiblioFacade.dedupeCitations(List.of(
				link("10.1/preprint", null, "A paper without a doi"),
				link("10.1/preprint", null, "Another paper without a doi"),
				link("10.1/preprint", null, "A paper without a doi")));

		assertEquals(2, deduped.size());
	}

	@Test
	public void linksWithNeitherIdentifierNorTitleAreKeptRatherThanCollapsed() {
		// this bit in a live run: an "unknown" dedupe key merged every unidentified
		// reference into one, turning 31 references into 17
		CitationReference source = CitationReference.create(
				Builder.recordReference(IdType.DOI, "10.1/preprint"), null, null);
		CitationReference blank = CitationReference.create(null, null, null);
		List<CitationLink> links = List.of(
				new ImmutableCitationLink(source, blank, Optional.of(1)),
				new ImmutableCitationLink(source, blank, Optional.of(2)),
				new ImmutableCitationLink(source, blank, Optional.of(3)));

		assertEquals(3, BiblioFacade.dedupeCitations(links).size());
	}

	@Test
	public void theCitationIndexSurvivesConstruction() {
		CitationReference source = CitationReference.create(
				Builder.recordReference(IdType.DOI, "10.1/preprint"), null, null);
		CitationReference target = CitationReference.create(
				Builder.recordReference(IdType.DOI, "10.1136/a"), null, null);

		assertEquals(Optional.of(3), new ImmutableCitationLink(source, target, Optional.of(3)).getIndex());
	}
}
