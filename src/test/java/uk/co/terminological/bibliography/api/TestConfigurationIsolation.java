package uk.co.terminological.bibliography.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * issue-021: reading the configuration must not change the JVM.
 *
 * {@code BibliographicApis.create} loaded the secrets file into {@code System.getProperties()}, which is the live
 * system property table rather than a copy. Every API key became a JVM-wide property — readable by any library in the
 * process and printed by anything that dumps system properties into a log — and values bled from one call to the next,
 * so a second configuration saw the first one's keys and could not tell them from its own.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestConfigurationIsolation {

	private static Path propertiesWith(String contents) throws Exception {
		var file = Files.createTempFile("biblio-config", ".prop");
		file.toFile()
			.deleteOnExit();
		Files.writeString(file, contents);
		return file;
	}

	@Test
	public void aSecretInTheFileDoesNotBecomeASystemProperty() throws Exception {
		var file = propertiesWith(
			"pubmed-apikey=not-a-real-key-21\ndeveloper-email=nobody@example.com\n"
		);

		BibliographicApis.create(file);

		assertNull(
			"the api key reached the JVM's global property table",
			System.getProperty("pubmed-apikey")
		);
		assertNull(
			"so did the developer email",
			System.getProperty("developer-email")
		);
	}

	@Test
	public void oneConfigurationDoesNotLeakIntoTheNext() throws Exception {
		var first = propertiesWith(
			"openalex-daily-credits=4321\nopenalex-search-credit-reserve=321\n"
		);
		var second = propertiesWith("developer-email=nobody@example.com\n");

		BibliographicApis.create(first);
		var apis = BibliographicApis.create(second);

		// the second file says nothing about credits, so the defaults apply: if the
		// first file's values are still visible, the two configurations are sharing
		// a table
		assertEquals(
			uk.co.terminological.bibliography.api.openalex.OpenAlexClient.DEFAULT_DAILY_CREDITS,
			apis.getOpenAlex()
				.creditsAvailable()
		);
	}

}
