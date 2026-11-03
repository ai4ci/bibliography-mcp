package uk.co.terminological.bibliography.api.opencitations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.junit.Test;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;

/**
 * Which identifiers OpenCitations can be asked about, and what it says back.
 *
 * Two halves of the same gap. On the way in, the client asked only about DOIs, so a record known by PMID got no
 * citations and no explanation (debt-002). On the way out, every identifier that was not a DOI became
 * {@link IdType#UNK} — including the OpenAlex handles that make up most of a response — so citation endpoints arrived
 * unjoinable even though this project can now resolve every one of them (issue-001, debt-011).
 *
 * The accepted schemes are measured against the live API, not assumed: {@code doi}, {@code pmid}, {@code isbn} and
 * {@code omid} answer 200; {@code pmcid} and {@code openalex} answer **400**.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestOpenCitationsIdentifiers {

	// ---- what we can ask about ----

	@Test
	public void aDoiIsAskedAboutAsADoi() {
		assertEquals(
			Optional.of("doi:10.1136/bmjqs-2018-008370"),
			OpenCitationsClient.queryIdFor(
				Builder.recordReference(IdType.DOI, "10.1136/bmjqs-2018-008370")
			)
		);
	}

	@Test
	public void aPmidIsAskedAboutRatherThanSkipped() {
		// the whole of debt-002: OpenCitations answers to a pmid, and this client
		// used to drop the reference on the floor
		assertEquals(
			Optional.of("pmid:30636200"),
			OpenCitationsClient
				.queryIdFor(Builder.recordReference(IdType.PMID, "30636200"))
		);
	}

	@Test
	public void anIsbnIsAskedAboutToo() {
		assertEquals(
			Optional.of("isbn:9781402096327"),
			OpenCitationsClient
				.queryIdFor(Builder.recordReference(IdType.ISBN, "9781402096327"))
		);
	}

	@Test
	public void anIdentifierTheApiRefusesIsNotSentAtAll() {
		// measured: pmcid and openalex both answer 400, so asking would spend a call
		// to be told no
		assertFalse(
			OpenCitationsClient
				.queryIdFor(Builder.recordReference(IdType.PMCID, "PMC6560460"))
				.isPresent()
		);
		assertFalse(
			OpenCitationsClient
				.queryIdFor(Builder.recordReference(IdType.OPENALEX, "W2910707576"))
				.isPresent()
		);
	}

	// ---- what comes back ----

	private static Optional<OpenCitationsCitation.Pid> pid(String pids) {
		return OpenCitationsCitation.pidFromPids(pids);
	}

	@Test
	public void aDoiIsStillPreferredWhenThereIsOne() {
		var found = pid(
			"omid:br/061301 doi:10.1136/bmjqs-2018-008370 openalex:W2910707576 pmid:30636200"
		);

		assertEquals(IdType.DOI, found.orElseThrow().type);
		assertEquals("10.1136/bmjqs-2018-008370", found.orElseThrow().value);
	}

	@Test
	public void anOpenAlexHandleIsAnOpenAlexIdentifierAndNotUnknown() {
		// 40 of the 42 cited entities in a real response carry one of these, and
		// this project can resolve every one of them since feat-011
		var found = pid("omid:br/061301 openalex:W2910707576");

		assertEquals(IdType.OPENALEX, found.orElseThrow().type);
		assertEquals("W2910707576", found.orElseThrow().value);
	}

	@Test
	public void aPmidIsPreferredOverAnOpenAlexHandle() {
		// both are resolvable, and a PMID joins more of this project's sources
		var found = pid("omid:br/061301 openalex:W2910707576 pmid:30636200");

		assertEquals(IdType.PMID, found.orElseThrow().type);
		assertEquals("30636200", found.orElseThrow().value);
	}

	@Test
	public void anIsbnIsNamedRatherThanUnknown() {
		var found = pid("omid:br/061301 isbn:9781402096327");

		assertEquals(IdType.ISBN, found.orElseThrow().type);
		assertEquals("9781402096327", found.orElseThrow().value);
	}

	@Test
	public void aSchemeThisProjectDoesNotModelKeepsItsWholeHandle() {
		// an omid or an arxiv id cannot be resolved here, but it still identifies
		// the entity, and a link with an identifier beats a link with none
		var found = pid("omid:br/0612345 arxiv:2103.00300");

		assertEquals(IdType.UNK, found.orElseThrow().type);
		assertTrue(
			found.orElseThrow().value, found.orElseThrow().value
				.contains(":")
		);
	}

	@Test
	public void anEmptyPidListIsNoIdentifierRatherThanABlankOne() {
		assertFalse(pid("").isPresent());
	}

	@Test
	public void theOldBareDoiShapeStillParses() {
		// v1 returned a plain doi with no scheme at all
		var found = pid("10.1136/bmjqs-2018-008370");

		assertEquals(IdType.DOI, found.orElseThrow().type);
	}

}
