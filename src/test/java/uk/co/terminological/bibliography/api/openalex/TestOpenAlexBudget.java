package uk.co.terminological.bibliography.api.openalex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import javax.ws.rs.core.MultivaluedMap;

import org.junit.After;
import org.junit.Test;

import com.sun.jersey.core.util.MultivaluedMapImpl;

import uk.co.terminological.bibliography.api.BiblioFacade;
import uk.co.terminological.bibliography.api.BibliographicApiException;

/**
 * The credit gate that lets OpenAlex be a search provider.
 *
 * OpenAlex is the only metered client here: a search costs 10 credits of a daily allowance, a filtered call 1, and a
 * lookup by identifier nothing. Searching is affordable — ~1,000 searches a day — but only if it cannot eat the budget
 * the citation crawl and the batched fetches need, so searches stop at a reserve while the cheap calls carry on.
 *
 * Offline throughout: no call is made, the budget is set by hand and the response headers are fed in directly.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestOpenAlexBudget {

	private static OpenAlexClient client() {
		return OpenAlexClient.create();
	}

	/** a budget is a day's allowance: setting one starts the day over, which is also what isolates these tests */
	@After
	public void resetBudget() {
		client().withBudget(
			OpenAlexClient.DEFAULT_DAILY_CREDITS,
			OpenAlexClient.DEFAULT_SEARCH_CREDIT_RESERVE
		);
	}

	/** what a response would have told us about the account's remaining allowance */
	private static void reportsRemaining(long remaining) {
		MultivaluedMap<String, String> headers = new MultivaluedMapImpl();
		headers.add("X-RateLimit-Remaining", Long.toString(remaining));
		client().updateRateLimits(headers);
	}

	@Test
	public void aFullBudgetAffordsASearch() {
		var api = client().withBudget(10000, 1000);
		assertEquals(10000, api.creditsAvailable());
		assertTrue(api.canAffordSearch());
	}

	@Test
	public void creditsSpentAreCountedAgainstTheCeilingUntilTheApiSaysOtherwise() {
		var api = client().withBudget(10000, 1000);
		api.rateLimit(10);
		assertEquals(10, api.creditsSpent());
		assertEquals(9990, api.creditsAvailable());
	}

	@Test
	public void theApisOwnCountBeatsOurs() {
		var api = client().withBudget(10000, 1000);
		api.rateLimit(10);
		// the account is shared between processes and days, so what the API reports is
		// the truth and the local count is only a stand-in until it has spoken
		reportsRemaining(4321);
		assertEquals(4321, api.creditsAvailable());
	}

	@Test
	public void searchStopsAtTheReserveWhileCheaperCallsCarryOn() {
		var api = client().withBudget(10000, 1000);
		reportsRemaining(1009);

		assertFalse(
			"a 10 credit search would leave less than the reserve",
			api.canAffordSearch()
		);
		assertTrue(
			"a 1 credit fetch or citation call is what the reserve is for",
			api.canAfford(1)
		);
	}

	@Test
	public void searchResumesWhenThereIsRoomAboveTheReserve() {
		var api = client().withBudget(10000, 1000);
		reportsRemaining(1010);
		assertTrue(api.canAffordSearch());
	}

	@Test
	public void anExhaustedBudgetRefusesEvenTheCheapCalls() {
		var api = client().withBudget(10000, 1000);
		reportsRemaining(0);
		assertFalse(api.canAfford(1));
		assertFalse(api.canAffordSearch());
	}

	@Test
	public void aSearchOverBudgetIsANamedRefusalNotAnEmptyResult() {
		var api = client().withBudget(10000, 1000);
		reportsRemaining(5);

		try {
			api.buildQuery()
				.allFields("photosynthesis")
				.execute();
			fail("an over-budget search should say so, not return nothing");
		} catch (BibliographicApiException e) {
			// issue-013: this codebase's habit is to turn a refusal into "found
			// nothing", which is indistinguishable from a search that worked
			assertTrue(
				e.getMessage()
					.toLowerCase()
					.contains("credit")
			);
		}
	}

	@Test
	public void theBudgetComesFromThePropertiesFile() throws java.io.IOException {
		java.nio.file.Path secrets = java.nio.file.Files
			.createTempFile("biblio-budget", ".prop");
		secrets.toFile()
			.deleteOnExit();
		java.nio.file.Files.writeString(
			secrets,
			"openalex-daily-credits=500\nopenalex-search-credit-reserve=200\n"
		);

		var apis = uk.co.terminological.bibliography.api.BibliographicApis
			.create(secrets);

		assertEquals(500, apis.getOpenAlex()
			.creditsAvailable());
		assertTrue(apis.getOpenAlex()
			.canAffordSearch());
		// 205 left, and a 10 credit search would drop under the configured 200 reserve
		reportsRemaining(205);
		assertFalse(apis.getOpenAlex()
			.canAffordSearch());
	}

	@Test
	public void openAlexIsASearchProvider() {
		assertTrue(
			BiblioFacade.defaultApis()
				.get("search")
				.contains("openalex")
		);
	}

	@Test
	public void aSkippedSearchIsEmptyRatherThanAnError()
			throws java.io.IOException {
		java.nio.file.Path secrets = java.nio.file.Files
			.createTempFile("biblio-no-secrets", ".prop");
		secrets.toFile()
			.deleteOnExit();
		var facade = new BiblioFacade(secrets)
			.withSearchApis(java.util.List.of("openalex"));
		// nothing like enough for a 10 credit search: the gate stops this before any
		// request is made, which is also why this test needs no network
		client().withBudget(5, 1000);

		var results = facade.search(
			uk.co.terminological.bibliography.client.ImmutableQuery.builder()
				.allFields("photosynthesis")
				.build()
		);

		assertTrue(results.isEmpty());
		assertEquals(0, client().creditsSpent());
	}

}
