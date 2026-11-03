package uk.co.terminological.bibliography.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.TestPaperFixtures;
import uk.co.terminological.bibliography.client.RecordFetcher;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Record;

/**
 * One contract, five clients: an answer comes back under the identifier it was asked about.
 *
 * issue-024. A client that answers under an identifier of its own choosing looks correct on its own — the record is
 * right and so is the key — and breaks the moment its answer is merged with another client's: {@code BiblioFacade.fetch}
 * merges by key, so a key nobody else uses becomes a second entry. The caller is handed one paper twice, and the merge
 * policy never sees that client's contribution beside the others, so the record under the asked-for key is poorer than
 * it should be.
 *
 * No single client's tests can state this, which is why it lives here and asks all of them the same question.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
@Category(LiveTest.class)
public class TestFetcherContractLive {

	private static BibliographicApis apis;
	private static BiblioFacade facade;

	@BeforeClass
	public static void setUp() throws Exception {
		var secrets = Paths.get(
			System.getProperty("user.home") + "/.private/apis/secrets.prop"
		);
		var cache = Paths.get("/tmp/ehcache/fetch-contract-test");
		apis = BibliographicApis.create(secrets, cache);
		facade = new BiblioFacade(secrets, cache);
	}

	private static ImmutableRecordReference doi() {
		return Builder
			.recordReference(IdType.DOI, TestPaperFixtures.PUBLISHED_DOI);
	}

	private static void answersUnderTheIdentifierItWasAsked(
			String name, RecordFetcher fetcher
	) {
		var asked = doi();
		Map<ImmutableRecordReference, ? extends Record> answer = fetcher
			.fetch(List.of(asked));

		if (answer.isEmpty()) {
			// a client that does not know the work says nothing, which is not a
			// breach of this contract — only a key of its own choosing is
			System.out.println("INFO: " + name + " knows nothing of it");
			return;
		}
		System.out.println(
			"INFO: " + name + " answered under " + answer.keySet()
		);
		assertEquals(
			name + " answered under a key of its own rather than the one it was"
					+ " asked about",
			List.of(asked), List.copyOf(answer.keySet())
		);
	}

	@Test
	public void crossrefAnswersUnderTheAskedIdentifier() {
		answersUnderTheIdentifierItWasAsked("crossref", apis.getCrossref());
	}

	@Test
	public void entrezAnswersUnderTheAskedIdentifier() {
		// the one that did not: it answered under the PMID it resolved to
		answersUnderTheIdentifierItWasAsked("entrez", apis.getEntrez());
	}

	@Test
	public void europePmcAnswersUnderTheAskedIdentifier() {
		answersUnderTheIdentifierItWasAsked("europepmc", apis.getEuropePMC());
	}

	@Test
	public void openAlexAnswersUnderTheAskedIdentifier() {
		answersUnderTheIdentifierItWasAsked("openalex", apis.getOpenAlex());
	}

	@Test
	public void medrxivAnswersUnderTheAskedIdentifier() {
		answersUnderTheIdentifierItWasAsked("medrxiv", apis.getMedrxiv());
	}

	@Test
	public void thePmidItResolvedToIsReportedAsAnotherIdentifier() {
		// the identifier a client discovers is not lost by keying under the asked
		// one: it belongs in getOtherIdentifiers, where the dedup and the merge
		// already look for it
		var answer = apis.getEntrez()
			.fetch(List.of(doi()));

		assertTrue("entrez knows this paper", !answer.isEmpty());
		var record = answer.values()
			.iterator()
			.next();
		assertEquals(
			TestPaperFixtures.PMID,
			record.getIdentifier(IdType.PMID)
				.orElse(null)
		);
	}

	@Test
	public void oneIdentifierAskedOfEveryApiIsOneRecord() {
		// what the contract is for: the facade merges by key, so one paper comes
		// back once rather than once per key any client chose
		var answer = facade.fetch(List.of(doi()));

		System.out.println("INFO: the facade answered under " + answer.keySet());
		assertEquals(
			"one identifier asked about, one record expected", 1, answer.size()
		);
		assertEquals(doi(), answer.keySet()
			.iterator()
			.next());
	}

}
