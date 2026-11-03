package uk.co.terminological.bibliography.mcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.TestPaperFixtures;

/**
 * A crawl reporting itself to a client that is following it.
 *
 * Live because this is the case notifications exist for: a crawl long enough to outlive the call that started it. A
 * crawl that finishes inside the call is answered by the response instead, which the offline tier asserts.
 *
 * <h2>The client has to be listening</h2>
 *
 * Progress for a background job cannot arrive on the stream of a call that has already returned, so a client that
 * wants it must hold the standalone stream open — {@code openConnectionOnStartup(true)} here. A client that does not
 * will simply hear nothing, which is worth knowing before concluding the server is silent.
 *
 * [TESTS](/design/test-scripts/test-001-mcp-protocol-smoke-test.md)
 */
@Category(LiveTest.class)
public class TestCrawlProgressLive {

	@Test
	public void aCrawlReportsItselfToAFollowingClient() throws Exception {
		Path dir = Files.createTempDirectory("biblio-progress-live");
		var secrets = Path
			.of(System.getProperty("user.home"), ".private", "apis", "secrets.prop");
		var properties = McpHttpServer.propertiesFrom(secrets);
		properties.setProperty(
			"repository-directory", dir.resolve("repositories")
				.toString()
		);
		var server = McpHttpServer.start(secrets, properties, 0);

		var heard = new CopyOnWriteArrayList<String>();
		var arrived = new CountDownLatch(1);
		var transport = HttpClientStreamableHttpTransport
			.builder("http://127.0.0.1:" + server.port())
			.endpoint(McpHttpServer.DEFAULT_ENDPOINT)
			.openConnectionOnStartup(true)
			.build();
		var client = McpClient.sync(transport)
			.requestTimeout(java.time.Duration.ofSeconds(60))
			.progressConsumer(n -> {
				heard.add(n.message());
				arrived.countDown();
			})
			.build();
		try {
			client.initialize();
			var result = client.callTool(
				CallToolRequest.builder()
					.name("crawl")
					.arguments(
						Map.of(
							"action", "start", "seeds",
							List.of(
								Map.of("id", TestPaperFixtures.CITATION_RICH_VOR_DOI)
							),
							"direction", "DOWN", "depth", 1, "maxRecords", 40,
							// stay on the call: progress belongs to a request that is
							// still in flight
							"waitSeconds", 60
						)
					)
					.progressToken("live-progress")
					.build()
			);
			assertFalse(result.toString(), result.isError());

			// the call itself waited for the crawl, so anything to hear has been
			// heard by now
			assertTrue(
				"no progress notification arrived; heard " + heard,
				arrived.await(5, TimeUnit.SECONDS)
			);
			System.out.println("INFO: heard " + heard);
			assertTrue(
				heard.toString(), heard.stream()
					.anyMatch(m -> m != null && m.contains("visited"))
			);
		} finally {
			client.closeGracefully();
			server.close();
		}
	}

}
