package uk.co.terminological.bibliography.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import uk.co.terminological.bibliography.api.BiblioFacade;
import uk.co.terminological.bibliography.api.crossref.CrossRefWork;

/**
 * issue-019: the server lists ~9,500 citation styles and, before this, offered no way to pick one, so the
 * {@code citation} field {@code recordJson} can produce was unreachable from an MCP client.
 *
 * Offline throughout: the facade is built from an empty properties file and no tool here makes a call — rendering is
 * citeproc-java over the record in hand.
 *
 * [TESTS](/design/test-scripts/test-001-mcp-protocol-smoke-test.md)
 */
public class TestCitationStyleTool {

	private static final ObjectMapper mapper = new ObjectMapper()
		.registerModule(new Jdk8Module());

	private static BiblioFacade facade;

	@BeforeClass
	public static void buildFacade() throws IOException {
		// no credentials: every client in it works unauthenticated, and nothing here
		// calls one
		Path empty = Files.createTempFile("biblio-no-secrets", ".prop");
		empty.toFile()
			.deleteOnExit();
		facade = new BiblioFacade(empty);
	}

	@After
	public void clearStyle() { McpTools.withCitationStyle(null); }

	private static McpTools.Tool tool(String name) {
		var state = new McpTools.State(facade, mapper, null);
		return McpTools.all(state)
			.stream()
			.filter(t -> t.name.equals(name))
			.findFirst()
			.orElse(null);
	}

	private static ObjectNode args(String style) {
		var args = mapper.createObjectNode();
		args.put("style", style);
		return args;
	}

	/** a real payload, so the renderer has the fields a style asks for */
	private static CrossRefWork work() throws IOException {
		return new CrossRefWork(
				mapper.readTree(
					"{\"DOI\":\"10.1136/bmjqs-2018-008370\",\"type\":\"journal-article\","
							+ "\"publisher\":\"BMJ\",\"title\":[\"Artificial intelligence, bias and clinical safety\"],"
							+ "\"container-title\":[\"BMJ Quality & Safety\"],"
							+ "\"author\":[{\"given\":\"Robert\",\"family\":\"Challen\"}],"
							+ "\"issued\":{\"date-parts\":[[2019,1,12]]}}"
				)
		);
	}

	@Test
	public void theServerOffersACitationStyleTool() {
		assertNotNull(
			"an MCP client has no way to select a style without this tool",
			tool("citation_style")
		);
	}

	@Test
	public void selectingAStyleMakesRecordsCarryARenderedCitation()
			throws IOException {
		var selected = tool("citation_style").handler.apply(args("ieee"));

		assertTrue(selected.toString()
			.contains("ieee"));
		var json = McpTools.recordJson(mapper, facade, work());
		assertTrue(
			"a record returned after a style is chosen should carry its citation",
			json.has("citation")
		);
		assertTrue(
			json.get("citation")
				.asText()
				.contains("Challen")
		);
	}

	@Test
	public void noStyleMeansNoCitationField() throws IOException {
		var json = McpTools.recordJson(mapper, facade, work());
		assertTrue(json.get("citation") == null);
	}

	@Test
	public void anUnknownStyleIsRefusedRatherThanSilentlyIgnored() {
		try {
			tool("citation_style").handler.apply(args("not-a-style"));
			fail("an unsupported style should be refused");
		} catch (IllegalArgumentException e) {
			assertTrue(
				e.getMessage()
					.contains("not-a-style")
			);
		}
	}

	@Test
	public void theStylesTheToolAcceptsAreTheOnesTheServerLists() {
		List<String> styles = facade.getSupportedCitationStyles();
		assertTrue(styles.contains("ieee"));
		// the two tools have to agree, or citation_styles advertises what
		// citation_style will refuse
		Optional<String> any = styles.stream()
			.filter(s -> s.equals("apa"))
			.findFirst();
		assertEquals(Optional.of("apa"), any);
	}

}
