package uk.co.terminological.bibliography.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import uk.co.terminological.bibliography.api.BiblioFacade;
import uk.co.terminological.bibliography.api.zotero.ZoteroClient;
import uk.co.terminological.bibliography.api.zotero.ZoteroConfig;
import uk.co.terminological.bibliography.repository.SqliteRepositoryStore;

/**
 * The MCP server: a long-lived local service, not a command a client spawns.
 *
 * It holds a durable repository and a citation crawl that outlives the request that started it, so it listens on a
 * loopback port and speaks MCP's streamable HTTP transport through the official SDK —
 * see <a href="file:../../../../../../../architecture/decision-records/adr-006-adopt-mcp-sdk-over-http.md">ADR-006</a>,
 * which replaced a hand-rolled JSON-RPC loop over stdio.
 *
 * <h2>Bound to loopback, and checked</h2>
 *
 * A port is reachable in ways a pipe never was. The listener is bound to {@code 127.0.0.1} and the SDK's header
 * validator is installed, because the transports validate neither {@code Origin} nor {@code Host} by default and a
 * page in a browser on the same machine can otherwise post to a local port. There is no authentication: this is a
 * service for the user who started it.
 */
// [IMPLEMENTS](/design/external-interfaces/interface-001-mcp-http-transport.md)
// [IMPLEMENTS](/design/features/feat-010-crawl-engine-with-zotero-mirror.md)
public class McpHttpServer implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(McpHttpServer.class);

	public static final String SERVER_NAME = "bibliographic-api-client";
	public static final String DEFAULT_ENDPOINT = "/mcp";
	public static final int DEFAULT_PORT = 8089;

	private final Server jetty;
	private final McpSyncServer mcp;
	private final BiblioContext context;

	private McpHttpServer(Server jetty, McpSyncServer mcp, BiblioContext context) {
		this.jetty = jetty;
		this.mcp = mcp;
		this.context = context;
	}

	public static void main(String[] args) throws Exception {
		// log4j to stderr. stdout is no longer a protocol channel, but a server that
		// writes to the terminal it was started from is still the friendlier one
		BasicConfigurator.configure(
			new ConsoleAppender(
					new PatternLayout("%d %-5p %c{1} - %m%n"),
					ConsoleAppender.SYSTEM_ERR
			)
		);

		var secretsPath = args.length > 0 ? args[0]
			: System.getenv()
				.getOrDefault(
					"BIBLIO_SECRETS_PATH",
					System.getProperty("user.home") + "/.private/apis/secrets.prop"
				);
		var properties = propertiesFrom(resolve(secretsPath));
		var port = Integer
			.parseInt(properties.getProperty("mcp-port", Integer.toString(DEFAULT_PORT)));

		var server = start(resolve(secretsPath), properties, port);
		Runtime.getRuntime()
			.addShutdownHook(new Thread(server::close, "mcp-shutdown"));
		server.join();
	}

	/**
	 * Build and start everything: the repository, the clients, the crawl engine, the MCP server and Jetty.
	 *
	 * Takes a port so a test can ask for an ephemeral one; port 0 means "whatever is free", and {@link #port()} says
	 * which was taken.
	 */
	public static McpHttpServer start(
			Path secretsPath, Properties properties, int port
	) throws Exception {
		var cacheDir = properties.getProperty("cache-directory");
		var facade = cacheDir == null
			? new BiblioFacade(secretsPath)
			: new BiblioFacade(secretsPath, resolve(cacheDir));

		var repository = repositoryFile(properties);
		log.info("repository: {}", repository);
		var store = SqliteRepositoryStore.open(repository);

		var zoteroConfig = ZoteroConfig.from(properties);
		var zotero = zoteroConfig.isEnabled() ? new ZoteroClient(zoteroConfig)
			: null;

		var pdfDirectory = repository.getParent()
			.resolve("pdfs");
		var context = new BiblioContext(store, facade, zotero, pdfDirectory);
		var json = McpJsonDefaults.getMapper();
		var transport = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(json)
			.mcpEndpoint(DEFAULT_ENDPOINT)
			.build();

		var mcp = McpServer.sync(transport)
			.serverInfo(SERVER_NAME, version())
			.capabilities(
				ServerCapabilities.builder()
					.tools(true)
					.build()
			)
			.instructions(
				"A citation crawl engine over the bibliographic APIs. Search to find seeds and collect them,"
						+ " crawl to expand the citation graph in the background, rank and select what matters,"
						+ " and sync the selection to Zotero."
			)
			.tools(BiblioTools.all(context, json))
			.build();

		var jetty = new Server(new InetSocketAddress("127.0.0.1", port));
		var handler = new ServletContextHandler("/");
		handler.addServlet(new ServletHolder(transport), DEFAULT_ENDPOINT + "/*");
		jetty.setHandler(handler);
		jetty.start();

		var server = new McpHttpServer(jetty, mcp, context);
		log.info(
			"MCP server listening on http://127.0.0.1:{}{}", server.port(),
			DEFAULT_ENDPOINT
		);
		return server;
	}

	/** The port actually bound, which is what an ephemeral port makes worth asking. */
	public int port() {
		return ((org.eclipse.jetty.server.ServerConnector) this.jetty
			.getConnectors()[0]).getLocalPort();
	}

	public String endpoint() {
		return "http://127.0.0.1:" + port() + DEFAULT_ENDPOINT;
	}

	public BiblioContext context() { return this.context; }

	public void join() throws InterruptedException { this.jetty.join(); }

	/**
	 * Stop serving, then stop crawling, then close the repository.
	 *
	 * That order on purpose: refuse new work first, so the crawl's last checkpoint is not racing a tool call that is
	 * still writing.
	 */
	@Override
	public void close() {
		try {
			this.jetty.stop();
		} catch (Exception e) {
			log.warn("could not stop the http server cleanly: {}", e.getMessage());
		}
		try {
			this.mcp.closeGracefully();
		} catch (Exception e) {
			log.warn("could not close the mcp server cleanly: {}", e.getMessage());
		}
		this.context.close();
		log.info("stopped");
	}

	// ---- configuration ------------------------------------------------------

	/**
	 * Where the repository lives, by ADR-005: user data under the XDG data directory, never the HTTP cache, which
	 * points under {@code /tmp} and is wipeable by design.
	 */
	static Path repositoryFile(Properties properties) {
		var override = properties.getProperty("repository-directory");
		Path root;
		if (override != null) {
			root = resolve(override);
		} else {
			var xdg = System.getenv("XDG_DATA_HOME");
			var base = xdg == null || xdg.isBlank()
				? Paths.get(System.getProperty("user.home"), ".local", "share")
				: Paths.get(xdg);
			root = base.resolve("bibliographic-api-client")
				.resolve("repositories");
		}
		var name = properties.getProperty("repository-name", "default");
		return root.resolve(name)
			.resolve("repository.db");
	}

	static Properties propertiesFrom(Path path) {
		// a fresh Properties, not System.getProperties(): loading secrets into the
		// JVM's global table is issue-021, and this server is not going to add to it
		var properties = new Properties();
		if (!Files.exists(path)) {
			log.warn("no properties file at {}; using defaults", path);
			return properties;
		}
		try (var in = Files.newInputStream(path)) {
			properties.load(in);
		} catch (IOException e) {
			log.warn("could not read {}: {}", path, e.getMessage());
		}
		return properties;
	}

	static Path resolve(String path) {
		return Paths
			.get(path.replaceFirst("^~", System.getProperty("user.home")));
	}

	private static String version() {
		var fromJar = McpHttpServer.class.getPackage()
			.getImplementationVersion();
		return fromJar == null ? "dev" : fromJar;
	}

}
