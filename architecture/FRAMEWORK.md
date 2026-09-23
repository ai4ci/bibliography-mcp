# Framework

* Java 11 source/target (`maven.compiler.source/target`), built with the Maven
  toolchain present on the machine (newer JDKs are fine).
* Maven build, parent POM `io.github.terminological:m2repo`, packaging `jar`.
* Sibling `-SNAPSHOT` dependencies (`fluentxml`, `exotic-datatypes`,
  `nlp-tools`) resolve from the local `~/.m2` repository; the remote ICM
  repository is unreliable (expired TLS certificate) and does not host them.
* HTTP clients: Jersey 1.12, wrapped by `CachingApiClient` which adds EHCache
  persistence, token-bucket rate limiting and per-URL+params caching keys.
* JSON: Jackson 2.9.7 with the `Jdk8Module` (Optional support).
* Graph and analysis: jgrapht (page rank), nlp-tools (corpus, LDA).
* References and citation styles: citeproc-java with the citationstyles
  `styles`/`locales` artefacts.
* PDF parsing: cermine.
* Logging: SLF4J API with the log4j 1.2 backend; the MCP server routes all
  logging to stderr because stdout is the protocol channel.
* MCP transport: hand-rolled newline-delimited JSON-RPC 2.0 over stdio
  ([ADR-002](/architecture/decision-records/adr-002-hand-rolled-mcp-protocol.md)).
* Packaging: `maven-shade-plugin` produces a runnable fat jar with
  `Main-Class: …mcp.McpServer` (~115 MB, dominated by cermine and the
  citation-style resources).
* Tests: JUnit 4 for unit tests; the API-level end-to-end tests are `main()`
  methods that hit live APIs and are deliberately not run by surefire; the MCP
  protocol is covered by [scripts/mcp-smoke-test.sh](/scripts/mcp-smoke-test.sh).
* Eclipse project metadata (`.classpath`, `.project`, `.settings`) is checked
  in, as is the agent access configuration (`.agentports`, `.agentreadonly`,
  `.agentreadwrite`).
