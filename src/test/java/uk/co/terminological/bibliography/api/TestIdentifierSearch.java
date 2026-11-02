package uk.co.terminological.bibliography.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import uk.co.terminological.bibliography.client.ImmutableQuery;
import uk.co.terminological.bibliography.client.Query;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * Asking for a paper by its identifier.
 *
 * A search is a text match, and a DOI put through one matches every paper that mentions it: searching
 * {@code 10.1136/bmj.n579} returned 305 works, 199 of them from a single API, when the caller wanted one paper. An
 * identifier names a record, so a query that is nothing but an identifier is a fetch (issue-023).
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestIdentifierSearch {

	private static Query lookingFor(String term) {
		return ImmutableQuery.builder()
			.allFields(term)
			.build();
	}

	private static Optional<ImmutableRecordReference> identifierIn(String term) {
		return BiblioFacade.identifierQuery(lookingFor(term));
	}

	@Test
	public void aDoiIsAnIdentifier() {
		var found = identifierIn("10.1136/bmj.n579");
		assertEquals(Optional.of(IdType.DOI), found.map(RecordReference::getIdentifierType));
		assertEquals(
			Optional.of("10.1136/bmj.n579"),
			found.flatMap(RecordReference::getIdentifier)
		);
	}

	@Test
	public void aDoiIsStillADoiWhenItArrivesAsAUrlOrWithItsScheme() {
		// what a user pastes: the resolver link, or the form a reference list prints
		for (String spelling : List.of(
			"https://doi.org/10.1136/bmj.n579",
			"http://dx.doi.org/10.1136/bmj.n579",
			"doi:10.1136/bmj.n579",
			"  10.1136/bmj.n579  "
		)) {
			assertEquals(
				spelling,
				Optional.of("10.1136/bmj.n579"),
				identifierIn(spelling).flatMap(RecordReference::getIdentifier)
			);
		}
	}

	@Test
	public void aPmcidIsAnIdentifier() {
		var found = identifierIn("PMC6560460");
		assertEquals(Optional.of(IdType.PMCID), found.map(RecordReference::getIdentifierType));
		assertEquals(
			Optional.of("PMC6560460"),
			found.flatMap(RecordReference::getIdentifier)
		);
	}

	@Test
	public void anOpenAlexWorkIdIsAnIdentifier() {
		assertEquals(
			Optional.of(IdType.OPENALEX),
			identifierIn("W2910707576").map(RecordReference::getIdentifierType)
		);
	}

	@Test
	public void aPmidIsAnIdentifierWhenItSaysSo() {
		var found = identifierIn("pmid:30636200");
		assertEquals(Optional.of(IdType.PMID), found.map(RecordReference::getIdentifierType));
		assertEquals(
			Optional.of("30636200"),
			found.flatMap(RecordReference::getIdentifier)
		);
	}

	@Test
	public void aBareNumberIsAPmidOnlyWhenItIsLongEnoughToBeOne() {
		// 30636200 is a PMID; 2018 is a year somebody typed into a search box, and
		// treating it as an identifier would turn an ordinary search into a lookup
		// that finds nothing
		assertEquals(
			Optional.of(IdType.PMID),
			identifierIn("30636200").map(RecordReference::getIdentifierType)
		);
		assertFalse(identifierIn("2018").isPresent());
		assertFalse(identifierIn("12").isPresent());
	}

	@Test
	public void ordinaryTermsAreNotIdentifiers() {
		for (String term : List.of(
			"malaria",
			"SARS-CoV-2 transmission",
			"artificial intelligence, bias and clinical safety",
			"10.1136",
			""
		)) {
			assertFalse(term, identifierIn(term).isPresent());
		}
	}

	@Test
	public void anIdentifierWithOtherConditionsIsStillASearch() {
		// "this DOI, by that author" is not a lookup: the caller is filtering, and a
		// fetch cannot honour the rest of what they asked for
		var qualified = ImmutableQuery.builder()
			.allFields("10.1136/bmj.n579")
			.addAuthors("Challen")
			.build();
		assertFalse(BiblioFacade.identifierQuery(qualified).isPresent());

		var windowed = ImmutableQuery.builder()
			.allFields("10.1136/bmj.n579")
			.year(2021)
			.build();
		assertFalse(BiblioFacade.identifierQuery(windowed).isPresent());
	}

	@Test
	public void searchingForAnIdentifierFetchesItInsteadOfMatchingText()
			throws IOException {
		var facade = new RecordingFacade();

		var found = facade.search(lookingFor("10.1136/bmj.n579"));

		assertEquals(
			"the identifier should have been fetched, not searched for",
			List.of("10.1136/bmj.n579"),
			facade.fetched
		);
		assertEquals(1, found.size());
		assertEquals(
			Optional.of("The paper itself"),
			found.iterator()
				.next()
				.getTitle()
		);
	}

	@Test
	public void anIdentifierNobodyKnowsReturnsNothingRatherThanEverything()
			throws IOException {
		// no fallback to text search: a mistyped DOI finding nothing says so, where
		// 305 papers that merely mention it would not
		var facade = new RecordingFacade();
		facade.findNothing = true;

		var found = facade.search(lookingFor("10.1136/bmj.n579"));

		assertTrue(found.isEmpty());
		assertEquals(List.of("10.1136/bmj.n579"), facade.fetched);
	}

	@Test
	public void oneIdentifierYieldsOneRecordHoweverTheClientsKeyIt()
			throws IOException {
		// Entrez answers a DOI fetch under the PMID it resolved to, while the others
		// answer under the DOI they were asked about, so BiblioFacade.fetch returns
		// the same paper twice (issue-024). One identifier was asked for, so one
		// record comes back.
		var facade = new TwoKeyedFacade();

		var found = facade.search(lookingFor("10.1136/bmj.n579"));

		assertEquals(1, found.size());
		var record = found.iterator()
			.next();
		assertEquals(
			Optional.of("10.1136/bmj.n579"),
			record.getIdentifier(IdType.DOI)
		);
		assertEquals(Optional.of("33687922"), record.getIdentifier(IdType.PMID));
	}

	/** a facade whose clients disagree about what to key an answer by, as the real ones do */
	private static class TwoKeyedFacade extends BiblioFacade {

		TwoKeyedFacade() throws IOException {
			super(noSecrets());
		}

		@Override
		public Map<ImmutableRecordReference, ? extends Record> fetch(
				Collection<? extends RecordReference> ids
		) {
			var byDoi = ImmutableRecordReference.builder()
				.identifierType(IdType.DOI)
				.identifier("10.1136/bmj.n579")
				.build();
			var byPmid = ImmutableRecordReference.builder()
				.identifierType(IdType.PMID)
				.identifier("33687922")
				.build();
			return Map.of(
				byDoi,
				new TestSearchDedup.StubRecord(
						IdType.DOI, "10.1136/bmj.n579", "The paper itself", "crossref"
				),
				byPmid,
				new TestSearchDedup.StubRecord(
						IdType.PMID, "33687922", "The paper itself", "entrez"
				)
			);
		}
	}

	/** a facade that records what it was asked to fetch and never calls an API */
	private static class RecordingFacade extends BiblioFacade {
		final List<String> fetched = new java.util.ArrayList<>();
		boolean findNothing = false;

		RecordingFacade() throws IOException {
			super(noSecrets());
		}

		@Override
		public Map<ImmutableRecordReference, ? extends Record> fetch(
				Collection<? extends RecordReference> ids
		) {
			ids.forEach(
				i -> i.getIdentifier()
					.ifPresent(this.fetched::add)
			);
			if (this.findNothing) return Map.of();
			var key = ids.iterator()
				.next()
				.asId();
			return Map.of(
				key,
				new TestSearchDedup.StubRecord(
						key.getIdentifierType(), key.getIdentifier()
							.get(),
						"The paper itself", "crossref"
				)
			);
		}
	}

	private static java.nio.file.Path noSecrets() throws IOException {
		var empty = java.nio.file.Files.createTempFile("biblio-no-secrets", ".prop");
		empty.toFile()
			.deleteOnExit();
		return empty;
	}

}
