package uk.co.terminological.bibliography;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.spi.LoggingEvent;
import org.isomorphism.util.TokenBuckets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * What the cache does when things are not perfect.
 *
 * Three records, one file. A cache written by an older build used to stop the server from starting at all
 * (debt-007); a 3-second read timeout made several APIs look empty rather than slow (debt-009); and the cache
 * directory is the thing every one of those failures is recovered by deleting, which is only a fix if a human knows
 * to do it.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestCacheResilience {

	/** the least a client can be: a cache, a rate limiter, and nothing else */
	static class Client extends CachingApiClient {
		Client(Path cacheDir) {
			super(
					Optional.of(cacheDir), TokenBuckets.builder()
						.withCapacity(10)
						.withInitialTokens(10)
						.withFixedIntervalRefillStrategy(10, 1, java.util.concurrent.TimeUnit.SECONDS)
						.build()
			);
		}

		@Override
		protected javax.ws.rs.core.MultivaluedMap<String, String> defaultApiParams() {
			return new com.sun.jersey.core.util.MultivaluedMapImpl();
		}
	}

	/** catches what the code logs, so the assertion is about behaviour rather than about a console */
	static class Captured extends AppenderSkeleton {
		final List<String> warnings = new ArrayList<>();

		@Override
		protected void append(LoggingEvent event) {
			if (event.getLevel()
				.isGreaterOrEqual(Level.WARN))
				this.warnings.add(String.valueOf(event.getMessage()));
		}
		@Override
		public void close() {}
		@Override
		public boolean requiresLayout() { return false; }
	}

	private Path dir;
	private Captured captured;

	@Before
	public void makeDir() throws Exception {
		this.dir = Files.createTempDirectory("biblio-cache-test");
		this.captured = new Captured();
		LogManager.getRootLogger()
			.addAppender(this.captured);
	}

	@After
	public void stopCapturing() {
		LogManager.getRootLogger()
			.removeAppender(this.captured);
	}

	// ---- debt-007: a cache from another build must not stop the server ----

	@Test
	public void aCacheWrittenByAnotherBuildIsRebuiltRatherThanFatal()
			throws Exception {
		var cacheDir = this.dir.resolve("cache");
		var client = new Client(cacheDir);
		client.shutdown();

		// what a changed serialVersionUID looks like from outside: a persistence
		// directory whose contents this build cannot read
		corrupt(cacheDir);

		// the server used to die here, in BibliographicApis.create, reading as a
		// configuration error rather than a stale cache
		var second = new Client(cacheDir);
		assertTrue(
			"the damaged cache was opened without complaint, so either it was not"
					+ " damaged or the failure is still silent: " + this.captured.warnings,
			this.captured.warnings.stream()
				.anyMatch(m -> m.contains("rebuilding it"))
		);
		assertTrue(
			"a cache this build cannot read should have been rebuilt",
			Files.isDirectory(cacheDir)
		);
		second.shutdown();
	}

	@Test
	public void rebuildingTheCacheLeavesAUsableOne() throws Exception {
		var cacheDir = this.dir.resolve("cache");
		new Client(cacheDir).shutdown();
		corrupt(cacheDir);

		var client = new Client(cacheDir);
		client.permanentCache()
			.put("a-key", BinaryData.from("some bytes".getBytes()));

		assertEquals(
			"some bytes",
			new String(
					client.permanentCache()
						.get("a-key")
						.byteArray
			)
		);
		client.shutdown();
	}

	/**
	 * Make the persistence directory unreadable to this build, the way a class change does.
	 *
	 * EHCache keeps the serialized {@code ObjectStreamClass} descriptors for every cached value type in a
	 * {@code value-serializer/holder-*.bin} file, and reads them back at init. That is the file named in the debt record's
	 * stack trace, and damaging it is the closest reproduction of a changed {@code serialVersionUID} that does not involve
	 * two builds.
	 */
	private static void corrupt(Path cacheDir) throws Exception {
		int damaged = 0;
		try (var files = Files.walk(cacheDir)) {
			for (Path file : files.filter(Files::isRegularFile)
				.toList()) {
				if (file.toString()
					.contains("value-serializer")) {
					Files.write(file, "not what ehcache wrote".getBytes());
					damaged++;
				}
			}
		}
		assertTrue(
			"nothing was damaged, so this test proves nothing about " + cacheDir,
			damaged > 0
		);
	}

	// ---- debt-009: a slow API must not look like an empty one ----

	@Test
	public void theDefaultReadTimeoutAllowsForASlowApi() {
		// 3 seconds was the default, and OpenCitations and api.biorxiv.org both
		// answer in 3-6 seconds: every call timed out, and a timeout reads as "this
		// API has no data" rather than as an error
		assertTrue(
			"the default read timeout is too short for the APIs this project uses: "
					+ CachingApiClient.DEFAULT_READ_TIMEOUT_MS,
			CachingApiClient.DEFAULT_READ_TIMEOUT_MS >= 10_000
		);
	}

	// ---- debt-006: an empty answer must not be cached forever ----

	@Test
	public void anEmptyAnswerIsNotKeptForever() {
		// an upstream gap at fetch time became a permanent "no data" for that
		// request, because an empty body was cached like any other
		assertFalse(
			"an empty response body should not be worth caching",
			CachingApiClient.worthCaching(new byte[0])
		);
		assertFalse(
			"a body that is nothing but whitespace is the same gap",
			CachingApiClient.worthCaching("  \n\t ".getBytes())
		);
		assertTrue(
			CachingApiClient.worthCaching("{\"results\":[1]}".getBytes())
		);
	}

}
