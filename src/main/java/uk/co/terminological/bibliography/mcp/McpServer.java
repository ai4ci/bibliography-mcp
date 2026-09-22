package uk.co.terminological.bibliography.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hand-rolled MCP stdio server: newline-delimited JSON-RPC 2.0 over stdin /
 * stdout. Exposes the bibliographic clients as MCP tools. Secrets path is
 * passed as the first CLI argument (with optional second argument for the
 * cache directory).
 *
 * ponytail: minimal protocol subset (initialize, ping, tools/list, tools/call)
 * — no resources, prompts or subscriptions; add if an MCP client asks for them.
 */
public class McpServer {

	static Logger log = LoggerFactory.getLogger(McpServer.class);

	static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";
	static final String SERVER_NAME = "bibliographic-api-client";

	public static void main(String[] args) throws IOException {

		// stdout is the protocol channel; all logging must go to stderr
		BasicConfigurator.configure(new ConsoleAppender(
				new PatternLayout("%d %-5p %c{1} - %m%n"),
				ConsoleAppender.SYSTEM_ERR
		));

		String secretsPath = args.length > 0
				? args[0]
				: System.getenv().getOrDefault("BIBLIO_SECRETS_PATH",
						System.getProperty("user.home") + "/.private/apis/secrets.prop");
		String cacheDir = args.length > 1 ? args[1] : null;

		BiblioFacade facade = cacheDir == null
				? new BiblioFacade(Paths.get(secretsPath.replaceFirst("^~", System.getProperty("user.home"))))
				: new BiblioFacade(
						Paths.get(secretsPath.replaceFirst("^~", System.getProperty("user.home"))),
						Paths.get(cacheDir.replaceFirst("^~", System.getProperty("user.home"))));

		ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());
		McpTools.State state = new McpTools.State(facade, mapper);
		List<McpTools.Tool> tools = McpTools.all(state);

		BufferedReader in = new BufferedReader(
				new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		while ((line = in.readLine()) != null) {
			line = line.trim();
			if (line.isEmpty()) continue;
			JsonNode request;
			try {
				request = mapper.readTree(line);
			} catch (IOException e) {
				send(mapper, error(mapper, null, -32700, "Parse error"));
				continue;
			}
			if (request == null || !request.isObject()) {
				send(mapper, error(mapper, null, -32600, "Invalid Request"));
				continue;
			}

			String method = request.has("method") ? request.get("method").asText() : null;
			boolean isNotification = !request.has("id");
			JsonNode id = isNotification ? null : request.get("id");

			if (method == null) {
				if (!isNotification) send(mapper, error(mapper, id, -32600, "Invalid Request"));
				continue;
			}

			if (isNotification) continue; // notifications/initialized etc. — nothing to do

			try {
				switch (method) {

				case "initialize":
					ObjectNode result = mapper.createObjectNode();
					result.put("protocolVersion", requestedProtocolVersion(request));
					result.putObject("capabilities")
						.putObject("tools");
					result.putObject("serverInfo")
						.put("name", SERVER_NAME)
						.put("version", SERVER_VERSION);
					send(mapper, ok(mapper, id, result));
					break;

				case "ping":
					send(mapper, ok(mapper, id, mapper.createObjectNode()));
					break;

				case "tools/list":
					ArrayNode toolList = mapper.createArrayNode();
					for (McpTools.Tool tool : tools) {
						ObjectNode t = mapper.createObjectNode();
						t.put("name", tool.name);
						t.put("description", tool.description);
						t.set("inputSchema", tool.inputSchema);
						toolList.add(t);
					}
					ObjectNode toolsResult = mapper.createObjectNode();
					toolsResult.set("tools", toolList);
					send(mapper, ok(mapper, id, toolsResult));
					break;

				case "tools/call":
					String name = request.get("params").has("name")
							? request.get("params").get("name").asText()
							: null;
					McpTools.Tool tool = tools.stream()
						.filter(t -> t.name.equals(name))
						.findFirst()
						.orElse(null);
					if (tool == null) {
						send(mapper, error(mapper, id, -32602, "Unknown tool: " + name));
						break;
					}
					ObjectNode arguments = request.get("params").has("arguments")
							&& request.get("params").get("arguments").isObject()
									? (ObjectNode) request.get("params").get("arguments")
									: mapper.createObjectNode();
					log.info("tools/call {} with arguments {}", name, arguments);
					Object out = tool.handler.apply(arguments);
					ObjectNode callResult = mapper.createObjectNode();
					ArrayNode content = mapper.createArrayNode();
					ObjectNode textContent = content.addObject();
					textContent.put("type", "text");
					if (out instanceof String) {
						textContent.put("text", (String) out);
					} else {
						textContent.put("text", mapper.writeValueAsString(out));
					}
					callResult.set("content", content);
					callResult.put("isError", false);
					send(mapper, ok(mapper, id, callResult));
					break;

				default:
					send(mapper, error(mapper, id, -32601, "Method not found: " + method));
				}

			} catch (Exception e) {
				log.error("Error handling {}", method, e);
				send(mapper, error(mapper, id, -32603, "Internal error: " + e.getMessage()));
			}
		}
	}

	/** project version, injected manually to keep this class dependency free */
	static final String SERVER_VERSION = "master-SNAPSHOT";

	private static String requestedProtocolVersion(JsonNode request) {
		JsonNode params = request.get("params");
		if (params != null && params.has("protocolVersion") && params.get("protocolVersion").isTextual())
			return params.get("protocolVersion").asText();
		return DEFAULT_PROTOCOL_VERSION;
	}

	private static ObjectNode ok(ObjectMapper mapper, JsonNode id, JsonNode result) {
		ObjectNode out = mapper.createObjectNode();
		out.put("jsonrpc", "2.0");
		out.set("id", id);
		out.set("result", result);
		return out;
	}

	private static ObjectNode error(ObjectMapper mapper, JsonNode id, int code, String message) {
		ObjectNode out = mapper.createObjectNode();
		out.put("jsonrpc", "2.0");
		if (id != null) out.set("id", id);
		out.putObject("error")
			.put("code", code)
			.put("message", message);
		return out;
	}

	private static void send(ObjectMapper mapper, ObjectNode response) throws IOException {
		// must be exactly one line of JSON on stdout
		System.out.println(mapper.writeValueAsString(response));
		System.out.flush();
	}

}
