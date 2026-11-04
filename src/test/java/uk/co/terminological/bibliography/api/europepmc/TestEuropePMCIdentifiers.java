package uk.co.terminological.bibliography.api.europepmc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.junit.Test;

import uk.co.terminological.bibliography.api.europepmc.EuropePMCClient.DataSources;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;

/**
 * Which identifiers Europe PMC can be asked about, and under which of its own sources.
 *
 * Europe PMC does not answer to a bare identifier: it wants one of its own source codes beside it, {@code MED} for a
 * journal article and {@code PMC} for a full-text record. That mapping used to be written out four times — once per
 * identifier type, in each of two directions — which is why a {@code DOI} branch was missing from both for so long: the
 * same gap had to be noticed and fixed twice, and was not noticed at all until a preprint's references came back empty
 * while the API held 31 (debt-010).
 *
 * These are facts about the API, so they are worth pinning without a call. The point of the test is that adding an
 * identifier type is one edit, and that this file fails if it is made in only one of the places that needs it.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestEuropePMCIdentifiers {

	@Test
	public void aPmidIsAMedlineRecord() {
		assertEquals(
			Optional.of(DataSources.MED), EuropePMCClient.directSource(IdType.PMID)
		);
	}

	@Test
	public void aPmcidIsAFullTextRecord() {
		assertEquals(
			Optional.of(DataSources.PMC), EuropePMCClient.directSource(IdType.PMCID)
		);
	}

	@Test
	public void aDoiHasToBeResolvedFirst() {
		// Europe PMC indexes preprints as PPR and journal articles as MED, so which
		// source holds a DOI is not knowable without asking: there is no direct answer
		assertFalse(
			EuropePMCClient.directSource(IdType.DOI)
				.isPresent()
		);
		assertTrue(
			"a DOI is still something this API can be asked about",
			EuropePMCClient.canBeAskedAbout(IdType.DOI)
		);
	}

	@Test
	public void whatEuropePmcCannotBeAskedAbout() {
		// ISBN, OpenAlex ids and arXiv ids have no Europe PMC source; asking spends a call
		// to be refused, and the caller should be able to tell that apart from "no
		// citations"
		for (IdType type : new IdType[] { IdType.ISBN, IdType.OPENALEX, IdType.ARXIV,
				IdType.UNK }) {
			assertFalse(
				type + " should not be askable", EuropePMCClient.canBeAskedAbout(type)
			);
		}
	}

	@Test
	public void aReferenceWithNothingToLookUpIsNotAskedAbout() {
		// the loops this replaces called getIdentifier().get() unguarded; a blank
		// identifier would have been sent to the API as a URL path segment and
		// answered with a 404, which reads as "no citations"
		var client = EuropePMCClient.create("test@example.org");

		assertFalse(
			client.epmcTarget(Builder.recordReference(IdType.PMID, "   "))
				.isPresent()
		);
		assertFalse(
			client.epmcTarget(Builder.recordReference(IdType.PMID, ""))
				.isPresent()
		);
	}

	@Test
	public void aPmidReferenceResolvesWithoutAskingTheApi() {
		var client = EuropePMCClient.create("test@example.org");

		var target = client
			.epmcTarget(Builder.recordReference(IdType.PMCID, "PMC6560460"));

		assertTrue(target.isPresent());
		assertEquals(
			DataSources.PMC, target.get()
				.getFirst()
		);
		assertEquals(
			"PMC6560460", target.get()
				.getSecond()
		);
	}

}
