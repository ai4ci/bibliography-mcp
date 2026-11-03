package uk.co.terminological.bibliography.crawl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.TestPaperFixtures;
import uk.co.terminological.bibliography.api.BiblioFacade;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.repository.SqliteRepositoryStore;

/**
 * One small crawl against the real APIs.
 *
 * Deliberately tiny — three seeds, one hop down, a hard ceiling — because the point is to prove that the engine and
 * the live clients fit together, not to measure a crawl. A reference list is finite and known at publication, so
 * {@code DOWN} is the cheap direction to prove it with; {@code UP} is open-ended and is what the ceilings exist for.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
@Category(LiveTest.class)
public class TestCrawlEngineLive {

	private static Set<ImmutableRecordReference> seeds() {
		Set<ImmutableRecordReference> out = new LinkedHashSet<>();
		out.add(
			Builder.recordReference(IdType.DOI, TestPaperFixtures.PUBLISHED_DOI)
		);
		out.add(
			Builder.recordReference(
				IdType.DOI, TestPaperFixtures.CITATION_RICH_VOR_DOI
			)
		);
		return out;
	}

	@Test
	public void aSmallCrawlFillsTheRepositoryAndReportsItself() throws Exception {
		Path dir = Files.createTempDirectory("biblio-crawl-live");
		var facade = new BiblioFacade(
				Paths.get(
					System.getProperty("user.home") + "/.private/apis/secrets.prop"
				), Paths.get("/tmp/ehcache/crawl-e2e-test")
		);

		try (var store = SqliteRepositoryStore.open(dir.resolve("repo.db"));
				var engine = new CrawlEngine(store, facade)) {

			var job = engine.runSynchronously(
				CrawlJob.of(seeds(), Direction.DOWN, 1, 200)
			);

			System.out.println(
				"INFO: " + job + " stoppedBy="
						+ job.getStoppedBy()
							.orElse("-")
						+ " calls=" + job.getCallsPerApi()
			);
			System.out.println(
				"INFO: repository holds " + store.recordCount() + " records and "
						+ store.citationCount() + " citations"
			);

			assertEquals(JobStatus.DONE, job.getStatus());
			assertFalse(
				"the crawl made no calls at all", job.getCallsPerApi()
					.isEmpty()
			);
			assertTrue(
				"a reference list should have reached the repository",
				store.citationCount() > 0
			);
			assertTrue(
				"the seeds and what they cite should be stored",
				store.recordCount() >= seeds().size()
			);
			// the frontier drained rather than the job hitting its ceiling: at depth 1
			// from two seeds there is nothing like 200 records to find
			assertEquals(
				"frontierDrained", job.getStoppedBy()
					.orElse(null)
			);
		}
	}

}
