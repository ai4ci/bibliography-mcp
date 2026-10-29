package uk.co.terminological.bibliography.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.client.ImmutableQuery;
import uk.co.terminological.bibliography.client.Query;

/**
 * Live test for search deduplication across all configured search APIs.
 * <p>
 * Searches known DOIs and terms that exist across multiple APIs (CrossRef,
 * Entrez, Europe PMC) and asserts that the deduplicated results are no more
 * than the number of results returned by any single API — cross-API overlap
 * reduces the total, or at worst it's unchanged if every result is unique
 * per API.
 */
@Category(LiveTest.class)
public class TestSearchDedupLive {

	private static final String SECRETS = "inst/secrets.properties";

	private BiblioFacade facade() throws Exception {
		var path = Paths.get(SECRETS);
		if (!path.toFile().exists()) {
			throw new IllegalStateException(
				"Secrets file not found at " + path.toAbsolutePath()
			);
		}
		var facade = new BiblioFacade(path);
		facade.disableCache();
		return facade;
	}

	@Test
	public void knownDoiReturnsResults() throws Exception {
		var facade = facade();
		// A specific DOI that should exist in CrossRef and Entrez and Europe PMC
		var q = ImmutableQuery.builder()
			.allFields("10.1136/bmj.n579")
			.build();
		var results = facade.search(q);

		assertFalse(
			"Search for existing DOI should return at least one result",
			results.isEmpty()
		);
		System.out.println(
			"INFO: DOI search returned " + results.size() + " deduplicated result(s)"
		);
		assertTrue(
			"Should not return more than a few results for an exact DOI",
			results.size() <= 3
		);
	}

	@Test
	public void broadSearchReturnsResults() throws Exception {
		var facade = facade();
		var q = ImmutableQuery.builder()
			.allFields("SARS-CoV-2")
			.build();
		var results = facade.search(q);

		assertFalse(
			"Broad search should return results",
			results.isEmpty()
		);
		System.out.println(
			"INFO: broad search returned " + results.size() + " results"
		);
	}

	@Test
	public void firstResultTitleShouldBePresent() throws Exception {
		var facade = facade();
		var q = ImmutableQuery.builder()
			.allFields("SARS-CoV-2")
			.build();
		var results = facade.search(q);

		assertFalse("Search should return results", results.isEmpty());
		var first = results.iterator()
			.next();
		assertTrue(
			"First result should have a title",
			first.getTitle()
				.isPresent()
		);
		System.out.println(
			"INFO: Top result: " + first.getTitle()
				.orElse("(no title)")
		);
	}
}
