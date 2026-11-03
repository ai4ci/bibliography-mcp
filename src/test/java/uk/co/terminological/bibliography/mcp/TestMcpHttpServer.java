package uk.co.terminological.bibliography.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * The server a client actually talks to, over the wire.
 *
 * A real MCP client against a real HTTP server on an ephemeral port — the SDK's own client, so the handshake,
 * the session, the SSE framing and the schema validation are all exercised rather than imitated. Offline: the tools
 * called here touch the repository and never an API.
 *
 * [TESTS](/design/test-scripts/test-001-mcp-protocol-smoke-test.md)
 */
public class TestMcpHttpServer {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static Path dir;
	private static McpHttpServer server;
	private static McpSyncClient client;

	@BeforeClass
	public static void start() throws Exception {
		dir = Files.createTempDirectory("biblio-http-test");
		var secrets = dir.resolve("secrets.prop");
		Files.writeString(
			secrets,
			"zotero-mode=off\nrepository-directory=" + dir.resolve("repositories")
					+ "\n"
		);

		var properties = McpHttpServer.propertiesFrom(secrets);
		// port 0: the operating system picks one that is free, so the test never
		// collides with a server the developer is running
		server = McpHttpServer.start(secrets, properties, 0);

		var transport = HttpClientStreamableHttpTransport.builder(
			"http://127.0.0.1:" + server.port()
		)
			.endpoint(McpHttpServer.DEFAULT_ENDPOINT)
			.build();
		client = McpClient.sync(transport)
			.requestTimeout(java.time.Duration.ofSeconds(30))
			.build();
		client.initialize();
	}

	@AfterClass
	public static void stop() {
		if (client != null) client.closeGracefully();
		if (server != null) server.close();
	}

	private static String textOf(CallToolResult result) {
		return result.content()
			.stream()
			.filter(c -> c instanceof TextContent)
			.map(c -> ((TextContent) c).text())
			.collect(Collectors.joining());
	}

	private static CallToolResult call(String tool, Map<String, Object> args) {
		return client.callTool(new CallToolRequest(tool, args));
	}

	@Test
	public void theServerAnnouncesItselfAndItsTools() {
		var tools = client.listTools()
			.tools()
			.stream()
			.map(t -> t.name())
			.collect(Collectors.toList());

		// the six of plan-012, and nothing else: the surface is the point
		assertTrue(tools.toString(), tools.contains("search"));
		assertTrue(tools.toString(), tools.contains("list_records"));
		assertTrue(tools.toString(), tools.contains("select"));
		assertFalse(
			"the primitives were deleted, not renamed: " + tools,
			tools.contains("collect_search") || tools.contains("session_status")
				|| tools.contains("expand_citation_graph")
		);
	}

	@Test
	public void anEmptyRepositoryListsNothingRatherThanFailing()
			throws Exception {
		var result = call("list_records", Map.of("filter", "selected"));

		assertFalse(textOf(result), result.isError());
		assertEquals(0, JSON.readTree(textOf(result))
			.size());
	}

	@Test
	public void selectingSomethingUnknownMarksNothingAndSaysSo()
			throws Exception {
		var result = call(
			"select", Map.of("ids", List.of(Map.of("id", "10.1/not-here")))
		);

		assertFalse(textOf(result), result.isError());
		var json = JSON.readTree(textOf(result));
		assertEquals(1, json.get("marked")
			.asInt());
		// nothing was in the repository to mark, so nothing is selected
		assertEquals(0, json.get("totalSelected")
			.asInt());
	}

	@Test
	public void anUnknownIdTypeIsRefusedByNameRatherThanGuessedAt()
			throws Exception {
		var result = call(
			"select",
			Map.of(
				"ids",
				List.of(Map.of("id", "x", "idType", "NONSENSE"))
			)
		);

		assertTrue("an unknown id type should be an error", result.isError());
		assertTrue(textOf(result), textOf(result)
			.contains("NONSENSE"));
	}

	@Test
	public void theSdkRejectsArgumentsTheSchemaForbids() {
		// select requires ids; the SDK validates against the schema before the
		// handler runs, which is a layer of argument checking the old server wrote
		// by hand
		var result = call("select", Map.of("value", true));

		assertTrue("a call missing a required argument should fail", result.isError());
		assertTrue(
			textOf(result), textOf(result)
				.toLowerCase()
				.contains("ids")
		);
	}

	@Test
	public void askingForTheCrawlBeforeOneHasRunSaysSoRatherThanFailing()
			throws Exception {
		var result = call("crawl", Map.of("action", "status"));

		assertFalse(textOf(result), result.isError());
		assertTrue(
			textOf(result), textOf(result)
				.contains("no crawl has been run")
		);
	}

	@Test
	public void aCrawlWithNothingToCrawlFromIsRefusedWithAdvice() {
		var result = call(
			"crawl", Map.of("action", "start", "fromSelection", true)
		);

		assertTrue("a crawl with no seeds should be refused", result.isError());
		assertTrue(
			textOf(result), textOf(result)
				.contains("select records first")
		);
	}

	@Test
	public void rankingCountsTheCitationsTheRepositoryHolds() throws Exception {
		var store = server.context()
			.store();
		store.putRecord(
			new uk.co.terminological.bibliography.repository.RecordFixture(
					"10.1/popular", "Cited twice"
			)
		);
		for (String citing : List.of("10.1/a", "10.1/b")) {
			store.putRecord(
				new uk.co.terminological.bibliography.repository.RecordFixture(
						citing, "Citing paper " + citing
				)
			);
			store.putCitation(
				uk.co.terminological.bibliography.record.Builder.citationLink(
					uk.co.terminological.bibliography.record.Builder
						.recordReference(
							uk.co.terminological.bibliography.record.IdType.DOI, citing
						),
					uk.co.terminological.bibliography.record.Builder
						.recordReference(
							uk.co.terminological.bibliography.record.IdType.DOI,
							"10.1/popular"
						),
					1
				)
			);
		}

		var result = call("rank", Map.of("method", "cited_by", "top", 5));

		assertFalse(textOf(result), result.isError());
		var json = JSON.readTree(textOf(result));
		assertTrue("nothing was ranked", json.size() > 0);
		assertEquals(
			"10.1/popular", json.get(0)
				.get("id")
				.asText()
		);
		assertEquals(
			2, json.get(0)
				.get("citedBy")
				.asInt()
		);
	}

	@Test
	public void pageRankOrdersTheGraphTheCrawlCollected() throws Exception {
		var result = call("rank", Map.of("method", "page_rank", "top", 5));

		assertFalse(textOf(result), result.isError());
		var json = JSON.readTree(textOf(result));
		// the graph from the previous test: two papers citing one
		assertTrue("page rank returned nothing", json.size() > 0);
		assertEquals(
			"the cited work should be the central one", "10.1/popular",
			json.get(0)
				.get("id")
				.asText()
		);
		assertTrue(
			"the score it was ordered by should be reported", json.get(0)
				.has("score")
		);
	}

	@Test
	public void theSixToolsAreTheWholeSurface() {
		var tools = client.listTools()
			.tools()
			.stream()
			.map(t -> t.name())
			.sorted()
			.collect(Collectors.toList());

		assertEquals(
			List.of(
				"crawl", "list_records", "rank", "search", "select", "zotero_sync"
			),
			tools
		);
	}

	@Test
	public void zoteroBeingOffIsAClearRefusalAndNotAStackTrace() {
		// the server was started with zotero-mode=off, and everything else still works
		var result = call("zotero_sync", Map.of("action", "collections"));

		assertTrue("a sync without Zotero should fail", result.isError());
		assertTrue(
			textOf(result), textOf(result)
				.contains("zotero-mode=off")
		);
	}

	@Test
	public void aRecordPutInTheRepositoryIsReadBackOverTheWire()
			throws Exception {
		// written through the store rather than through search, so this stays offline
		server.context()
			.store()
			.putRecord(
				new uk.co.terminological.bibliography.repository.RecordFixture(
						"10.1/wire", "A record over the wire"
				)
			);

		var result = call("list_records", Map.of());

		assertFalse(textOf(result), result.isError());
		var json = JSON.readTree(textOf(result));
		var titles = new java.util.ArrayList<String>();
		json.forEach(
			r -> titles.add(
				r.get("title") == null ? "" : r.get("title")
					.asText()
			)
		);
		assertTrue(titles.toString(), titles.contains("A record over the wire"));
	}

}
