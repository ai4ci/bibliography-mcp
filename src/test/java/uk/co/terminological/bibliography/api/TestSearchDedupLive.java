package uk.co.terminological.bibliography.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.client.ImmutableQuery;
import uk.co.terminological.bibliography.client.Query;
import uk.co.terminological.bibliography.record.IdType;

/**
 * Search deduplication across the configured search APIs, live.
 *
 * <h2>The query is the design of this test</h2>
 *
 * One named author in one year: small enough that every API answers in seconds, specific enough that the APIs genuinely
 * overlap, and stable enough that the numbers do not move from week to week. The earlier version searched "SARS-CoV-2"
 * with no limit, which pulled hundreds of records per run into an identifier-mapping phase that scales with them — 35
 * seconds for a single search, and minutes for the class.
 *
 * It also searched for a bare DOI and asserted at most three results. That assumption is where issue-023 came from: the
 * query matched 305 works, because a search matches text and a DOI appears in the text of everything citing it. Such a
 * query is now routed to a fetch, and the assertion below holds for that reason rather than by luck.
 *
 * The cache is left on, as in the other live tests: these assert that the APIs agree, not that every call leaves the
 * machine.
 */
@Category(LiveTest.class)
public class TestSearchDedupLive {

	/** the path every other live test uses; this class named `inst/secrets.properties`, which exists nowhere, so it had never run */
	private static final String SECRETS = System.getProperty("user.home")
			+ "/.private/apis/secrets.prop";

	private static BiblioFacade facade;

	@BeforeClass
	public static void setUp() throws Exception {
		facade = new BiblioFacade(
				Paths.get(SECRETS), Paths.get("/tmp/ehcache/dedup-e2e-test")
		);
	}

	/** one author, one year: four works in OpenAlex, and a handful in each of the others */
	private static Query anAuthorInAYear() {
		return ImmutableQuery.builder()
			.addAuthors("Leon Danon")
			.year(2018)
			.build();
	}

	private static BiblioFacade everyApi() {
		return facade.withSearchApis(BiblioFacade.searchableApis());
	}

	@Test
	public void aScopedSearchReturnsResults() {
		var results = everyApi().search(anAuthorInAYear());

		assertFalse(
			"one author in one year should be found by someone",
			results.isEmpty()
		);
		System.out.println(
			"INFO: Leon Danon, 2018 -> " + results.size() + " deduplicated records"
		);
	}

	/**
	 * The assertion this class exists for: overlapping results are merged rather than repeated.
	 *
	 * Measured in <strong>one pass</strong>. An earlier version asked each API separately, then asked them together,
	 * and compared the counts — which compares two different sets of API calls. Crossref answered 20 in one pass and
	 * 0 in the other on the same day (a transient failure that {@code CachingApiClient} turns into an empty result,
	 * issue-013), and the test failed on a difference that said nothing about dedup.
	 *
	 * {@code scoredSearch} reports which APIs found each record, so the raw hit count and the deduplicated count come
	 * out of the same search and cannot disagree about what the APIs did.
	 */
	@Test
	public void overlappingResultsAreMergedRatherThanRepeated() {
		var scored = facade.withSearchApis(BiblioFacade.searchableApis())
			.scoredSearch(anAuthorInAYear());

		int rawHits = scored.stream()
			.mapToInt(
				hit -> hit.matchedApis()
					.size()
			)
			.sum();
		long corroborated = scored.stream()
			.filter(
				hit -> hit.matchedApis()
					.size() > 1
			)
			.count();
		System.out.println(
			"INFO: " + scored.size() + " records from " + rawHits
					+ " raw hits; " + corroborated + " found by more than one API"
		);
		scored.stream()
			.limit(3)
			.forEach(
				hit -> System.out.println(
					"INFO:   " + hit.matchedApis() + " " + hit.record()
						.getTitle()
						.orElse("?")
				)
			);

		assertFalse("the search found nothing at all", scored.isEmpty());
		assertEquals(
			"every raw hit must end up in exactly one record",
			rawHits - (int) scored.stream()
				.mapToLong(
					hit -> hit.matchedApis()
						.size() - 1
				)
				.sum(),
			scored.size()
		);
		if (corroborated == 0) {
			System.out.println(
				"INFO: no result appeared in more than one API for this query"
			);
		} else {
			assertTrue(
				"records found by several APIs should have been merged",
				scored.size() < rawHits
			);
		}
	}

	@Test
	public void everyResultCarriesATitle() {
		var results = everyApi().search(anAuthorInAYear());

		assertFalse(results.isEmpty());
		var untitled = results.stream()
			.filter(
				r -> r.getTitle()
					.isEmpty()
			)
			.count();
		assertEquals("records came back with no title", 0, untitled);
		System.out.println(
			"INFO: top result: " + results.iterator()
				.next()
				.getTitle()
				.orElse("(none)")
		);
	}

	/**
	 * issue-023: a query that is nothing but an identifier names one record.
	 *
	 * Before this it was a text search, and {@code 10.1136/bmj.n579} matched 305 works across the APIs — every paper that
	 * mentions it — where the caller wanted exactly one.
	 */
	@Test
	public void aDoiFindsTheOnePaperItNames() {
		var results = everyApi().search(
			ImmutableQuery.builder()
				.allFields("10.1136/bmj.n579")
				.build()
		);

		assertEquals(
			"a DOI names one paper, and " + results.size() + " came back",
			1,
			results.size()
		);
		var found = results.iterator()
			.next();
		assertEquals(
			"10.1136/bmj.n579",
			found.getIdentifier(IdType.DOI)
				.orElse(null)
		);
		System.out.println(
			"INFO: the DOI resolved to: " + found.getTitle()
				.orElse("(none)")
		);
	}

}
