package uk.co.terminological.bibliography.mcp;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

	@Test
	public void theSearchToolOffersOpenAlexAsAnApi() {
		var apis = tool("search").inputSchema.get("properties")
			.get("apis")
			.get("items")
			.get("enum")
			.toString();
		assertTrue(apis, apis.contains("openalex"));
	}

}
