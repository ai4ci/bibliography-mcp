package uk.co.terminological.bibliography.api.opencitations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import uk.co.terminological.bibliography.record.IdType;

/**
 * What the identifier enum is for, decided once.
 *
 * debt-011. {@code IdType} carried a value nothing constructed ({@code MID}) and lacked the one value several sources
 * actually emit ({@code ARXIV}), so the enum had no principle. The principle it now has: <strong>it names what sources
 * actually emit</strong> — nothing speculative, nothing an API could send and this project could not name.
 *
 * Each member is stated here with the reason it earns its place, so adding or removing one is a decision this file
 * argues with rather than a line in an enum. It lives in this package because the parser it asserts against is
 * package-private, which is the right visibility for a fact about one API's response shape.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestIdTypeMembership {

	@Test
	public void anArxivHandleIsNamedRatherThanUnknown() {
		// OpenCitations returns arxiv: handles for cited entities with no DOI —
		// about 1 in 120, and the only kind of citation endpoint this project
		// could not name
		var found = OpenCitationsCitation.pidFromPids("arxiv:2103.00300")
			.orElseThrow();

		assertEquals(IdType.ARXIV, found.type);
		assertEquals("2103.00300", found.value);
	}

	@Test
	public void anArxivHandleLosesToADoiLikeEveryOtherScheme() {
		// the scheme order is "how much of the graph it joins", and a DOI joins all
		// of it; an arxiv handle should only be reached when nothing better is there
		var found = OpenCitationsCitation
			.pidFromPids("doi:10.1136/bmj.n579 arxiv:2103.00300")
			.orElseThrow();

		assertEquals(IdType.DOI, found.type);
	}

	@Test
	public void theEnumHoldsExactlyWhatSourcesEmit() {
		// written as membership rather than absence, so that adding a value
		// requires a source that emits it to be named here
		assertEquals(
			java.util.List.of(
					IdType.PMCID, IdType.PMID, IdType.DOI, IdType.ISBN,
					IdType.OPENALEX, IdType.ARXIV, IdType.UNK
			),
			java.util.List.of(IdType.values())
		);
	}

}
