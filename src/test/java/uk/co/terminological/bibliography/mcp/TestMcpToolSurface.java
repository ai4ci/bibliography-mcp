package uk.co.terminological.bibliography.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import uk.co.terminological.bibliography.api.BiblioFacade;

/**
 * What the tool definitions promise an agent. A capability the server has and the schema does not name is a capability
 * no agent will use, which is how issue-019 happened.
 *
 * [TESTS](/design/external-interfaces/interface-001-mcp-stdio-protocol.md)
 */
public class TestMcpToolSurface {

	private static final ObjectMapper mapper = new ObjectMapper()
		.registerModule(new Jdk8Module());

	private static BiblioFacade facade;

	@BeforeClass
	public static void buildFacade() throws IOException {
		Path empty = Files.createTempFile("biblio-no-secrets", ".prop");
		empty.toFile()
			.deleteOnExit();
		facade = new BiblioFacade(empty);
	}

	private static McpTools.Tool tool(String name) {
		var state = new McpTools.State(facade, mapper, null);
		return McpTools.all(state)
			.stream()
			.filter(t -> t.name.equals(name))
			.findFirst()
			.orElseThrow();
	}

	/** the `apis` enum the search tool advertises, in order */
	private static List<String> advertisedSearchApis() {
		List<String> out = new ArrayList<>();
		tool("search").inputSchema.get("properties")
			.get("apis")
			.get("items")
			.get("enum")
			.forEach(n -> out.add(n.asText()));
		return out;
	}

	@Test
	public void theSearchToolOffersOpenAlexAsAnApi() {
		assertTrue(
			advertisedSearchApis().toString(),
			advertisedSearchApis()
				.contains("openalex")
		);
	}

	@Test
	public void theSearchToolOffersExactlyTheApisTheFacadeCanSearch() {
		// issue-020: the enum named an API the facade cannot route, so asking for it
		// was a search that could only ever return nothing. One list, named here, is
		// what stops the two drifting apart again.
		assertEquals(BiblioFacade.searchableApis(), advertisedSearchApis());
	}

	@Test
	public void medrxivIsNotOfferedAsASearchApi() {
		// not an oversight: bioRxiv serves a Cloudflare challenge to any programmatic
		// client, and preprints are searched through Europe PMC instead (issue-015)
		assertFalse(
			advertisedSearchApis()
				.contains("medrxiv")
		);
	}

}
