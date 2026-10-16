package uk.co.terminological.bibliography.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import uk.co.terminological.bibliography.api.zotero.ZoteroClient;
import uk.co.terminological.bibliography.api.zotero.ZoteroConfig;

/**
 * Hand-rolled MCP stdio server: newline-delimited JSON-RPC 2.0 over stdin / stdout. Exposes the bibliographic clients
 * as MCP tools. Secrets path is passed as the first CLI argument (with optional second argument for the cache
 * directory).
 *
 * ponytail: minimal protocol subset (initialize, ping, tools/list, tools/call) — no resources, prompts or
 * subscriptions; add if an MCP client asks for them.
 */
// [IMPLEMENTS](/design/features/feat-001-expose-bibliographic-apis-over-mcp.md)
// [IMPLEMENTS](/design/external-interfaces/interface-001-mcp-stdio-protocol.md)
// [IMPLEMENTS](/design/external-interfaces/interface-002-configuration-and-secrets.md)
@Deprecated
public class McpServer {

	@Deprecated
	static Logger log = LoggerFactory.getLogger(McpServer.class);

	@Deprecated
	static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";
	@Deprecated
	static final String SERVER_NAME = "bibliographic-api-client";

	@Deprecated
	public static void main(String[] args) throws IOException {

		// stdout is the protocol channel; all logging must go to stderr
		BasicConfigurator.configure(
			new ConsoleAppender(
					new PatternLayout("%d %-5p %c{1} - %m%n"),
					ConsoleAppender.SYSTEM_ERR
			)
		);

		var secretsPath = args.length > 0 ? args[0] : System.getenv()
			.getOrDefault(
				"BIBLIO_SECRETS_PATH",
				System.getProperty("user.home") + "/.private/apis/secrets.prop"
			);
		var cacheDir = args.length > 1 ? args[1] : null;

		var facade = cacheDir == null
			? new BiblioFacade(
					Paths
						.get(
							secretsPath
								.replaceFirst("^~", System.getProperty("user.home"))
						)
			)
			: new BiblioFacade(
					Paths.get(
						secretsPath
							.replaceFirst("^~", System.getProperty("user.home"))
					),
					Paths.get(
						cacheDir.replaceFirst("^~", System.getProperty("user.home"))
					)
			);

		// the same properties file carries the API credentials and the Zotero settings
		var properties = new Properties();
		var configPath = Paths
			.get(secretsPath.replaceFirst("^~", System.getProperty("user.home")));
		try (var in = Files.newInputStream(configPath)) {
			properties.load(in);
		} catch (IOException e) {
			log.warn(
				"could not read the properties file at {}: {}",
				configPath,
				e.getMessage()
			);
		}
		// Zotero is optional and checked lazily: a missing Zotero must not stop the
		// server, only the Zotero tools
		var zoteroConfig = ZoteroConfig.from(properties);
		var zotero = zoteroConfig.isEnabled()
			? new ZoteroClient(zoteroConfig) : null;

		var mapper = new ObjectMapper().registerModule(new Jdk8Module());
		var state = new McpTools.State(facade, mapper, zotero);
		var tools = McpTools.all(state);

		var in = new BufferedReader(
				new InputStreamReader(System.in, StandardCharsets.UTF_8)
		);
		String line;
		while ((line = in.readLine()) != null) {
			line = line.trim();
			if (line.isEmpty()) { continue; }
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

			var method = request.has("method") ? request.get("method")
				.asText() : null;
			var isNotification = !request.has("id");
			var id = isNotification ? null : request.get("id");

			if (method == null) {
				if (!isNotification) {
					send(mapper, error(mapper, id, -32600, "Invalid Request"));
				}
				continue;
			}

			if (isNotification) {
				continue; // notifications/initialized etc. — nothing to do
			}

			try {
				switch (method) {

				case "initialize":
					var result = mapper.createObjectNode();
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
					var toolList = mapper.createArrayNode();
					for (McpTools.Tool tool : tools) {
						var t = mapper.createObjectNode();
						t.put("name", tool.name);
						t.put("description", tool.description);
						t.set("inputSchema", tool.inputSchema);
						toolList.add(t);
					}
					var toolsResult = mapper.createObjectNode();
					toolsResult.set("tools", toolList);
					send(mapper, ok(mapper, id, toolsResult));
					break;

				case "tools/call":
					var name = request.get("params")
						.has("name")
							? request.get("params")
								.get("name")
								.asText()
							: null;
					var tool = tools.stream()
						.filter(t -> t.name.equals(name))
						.findFirst()
						.orElse(null);
					if (tool == null) {
						send(
							mapper,
							error(mapper, id, -32602, "Unknown tool: " + name)
						);
						break;
					}
					var arguments = request.get("params")
						.has("arguments")
							&& request.get("params")
								.get("arguments")
								.isObject()
									? (ObjectNode) request.get("params")
										.get("arguments")
									: mapper.createObjectNode();
					log.info("tools/call {} with arguments {}", name, arguments);
					var out = tool.handler.apply(arguments);
					var callResult = mapper.createObjectNode();
					var content = mapper.createArrayNode();
					var textContent = content.addObject();
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
					send(
						mapper,
						error(mapper, id, -32601, "Method not found: " + method)
					);
				}

			} catch (Exception e) {
				log.error("Error handling {}", method, e);
				send(
					mapper,
					error(mapper, id, -32603, "Internal error: " + e.getMessage())
				);
			}
		}
	}

	/** project version, injected manually to keep this class dependency free */
	@Deprecated
	static final String SERVER_VERSION = "master-SNAPSHOT";

	private static String requestedProtocolVersion(JsonNode request) {
		var params = request.get("params");
		if (params != null && params.has("protocolVersion")
				&& params.get("protocolVersion")
					.isTextual())
			return params.get("protocolVersion")
				.asText();
		return DEFAULT_PROTOCOL_VERSION;
	}

	private static ObjectNode ok(
			ObjectMapper mapper, JsonNode id, JsonNode result
	) {
		var out = mapper.createObjectNode();
		out.put("jsonrpc", "2.0");
		out.set("id", id);
		out.set("result", result);
		return out;
	}

	private static ObjectNode error(
			ObjectMapper mapper, JsonNode id, int code, String message
	) {
		var out = mapper.createObjectNode();
		out.put("jsonrpc", "2.0");
		if (id != null) { out.set("id", id); }
		out.putObject("error")
			.put("code", code)
			.put("message", message);
		return out;
	}

	private static void send(ObjectMapper mapper, ObjectNode response)
			throws IOException {
		// must be exactly one line of JSON on stdout
		System.out.println(mapper.writeValueAsString(response));
		System.out.flush();
	}

}
