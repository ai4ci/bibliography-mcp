package uk.co.terminological.bibliography.mcp;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import uk.co.terminological.bibliography.api.crossref.CrossRefWork;
import uk.co.terminological.bibliography.api.zotero.MetadataComparison;
import uk.co.terminological.bibliography.api.zotero.ZoteroClient;
import uk.co.terminological.bibliography.api.zotero.ZoteroCollection;
import uk.co.terminological.bibliography.api.zotero.ZoteroItem;
import uk.co.terminological.bibliography.api.zotero.ZoteroItemWriter;
import uk.co.terminological.bibliography.api.zotero.ZoteroLibraryIndex;
import uk.co.terminological.bibliography.client.ImmutableQuery;
import uk.co.terminological.bibliography.client.Query;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.datatypes.Tuple;

/**
 * MCP tool definitions over the bibliographic facade and session. Each tool takes an ObjectNode of arguments and
 * returns a Jackson node / list for serialisation into the tool response.
 */
// [IMPLEMENTS](/design/features/feat-001-expose-bibliographic-apis-over-mcp.md)
// [IMPLEMENTS](/design/features/feat-002-explore-citation-networks-in-a-session.md)
// [IMPLEMENTS](/design/features/feat-003-analyse-citation-graphs-and-topics.md)
// [IMPLEMENTS](/design/external-interfaces/interface-001-mcp-stdio-protocol.md)
// [IMPLEMENTS](/design/features/feat-006-classify-records-by-type-with-provenance.md)
// [IMPLEMENTS](/design/features/feat-008-clean-up-collection-metadata.md)
@Deprecated
public class McpTools {

	@Deprecated
	static Logger log = LoggerFactory.getLogger(McpTools.class);

	/** Server state shared by all tool handlers; session is swappable via load. */
	@Deprecated
	public static class State {
		@Deprecated
		public final BiblioFacade facade;
		@Deprecated
		public final ObjectMapper mapper;
		@Deprecated
		public final AtomicReference<Session> session;
		/** the Zotero bridge, or null when Zotero support is switched off */
		@Deprecated
		public final ZoteroClient zotero;
		/** the library match index, built on first use and dropped after a write */
		@Deprecated
		public final AtomicReference<ZoteroLibraryIndex> libraryIndex = new AtomicReference<>();

		@Deprecated
		public State(
				BiblioFacade facade, ObjectMapper mapper, ZoteroClient zotero
		) {
			this.facade = facade;
			this.mapper = mapper;
			this.session = new AtomicReference<>(facade.getSession());
			this.zotero = zotero;
		}

		@Deprecated
		public ZoteroClient requireZotero() {
			if (this.zotero == null) throw new IllegalStateException(
					"Zotero support is switched off (zotero-mode=off in the properties file)"
			);
			this.zotero.checkAvailable();
			return this.zotero;
		}

		@Deprecated
		public ZoteroLibraryIndex requireIndex() {
			var current = this.libraryIndex.get();
			if (current == null) {
				current = this.requireZotero()
					.buildIndex();
				this.libraryIndex.set(current);
			}
			return current;
		}
	}

	@Deprecated
	public static class Tool {
		@Deprecated
		public final String name;
		@Deprecated
		public final String description;
		@Deprecated
		public final ObjectNode inputSchema;
		@Deprecated
		public final Function<ObjectNode, Object> handler;

		@Deprecated
		public Tool(
				String name, String description, ObjectNode inputSchema,
				Function<ObjectNode, Object> handler
		) {
			this.name = name;
			this.description = description;
			this.inputSchema = inputSchema;
			this.handler = handler;
		}
	}

	private static ObjectNode schema(ObjectMapper m, String json) {
		try {
			return (ObjectNode) m.readTree(json);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static ObjectNode emptySchema(ObjectMapper m) {
		return schema(m, "{\"type\":\"object\",\"properties\":{}}");
	}

	private static final String IDS_SCHEMA = "{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":\"array\",\"items\":"
			+ "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"idType\":{\"type\":\"string\"}},"
			+ "\"required\":[\"id\",\"idType\"]}}},\"required\":[\"ids\"]}";

	private static Optional<LocalDate> optDate(ObjectNode args, String key) {
		var n = args.get(key);
		if (n == null || n.isNull()) return Optional.empty();
		return Optional.of(LocalDate.parse(n.asText()));
	}

	private static Optional<Integer> optInt(ObjectNode args, String key) {
		var n = args.get(key);
		if (n == null || n.isNull()) return Optional.empty();
		return Optional.of(n.asInt());
	}

	private static Optional<List<String>> optStringList(
			ObjectNode args, String key
	) {
		var n = args.get(key);
		if (n == null || n.isNull() || !n.isArray()) return Optional.empty();
		List<String> out = new ArrayList<>();
		n.forEach(x -> out.add(x.asText()));
		return Optional.of(out);
	}

	private static Set<ImmutableRecordReference> ids(ObjectNode args) {
		Set<ImmutableRecordReference> out = new HashSet<>();
		var arr = args.get("ids");
		if (arr != null && arr.isArray()) {
			for (JsonNode n : arr) {
				var id = n.has("id") ? n.get("id")
					.asText() : null;
				var type = n.has("idType") ? n.get("idType")
					.asText() : null;
				if (n.isTextual()) { id = n.asText(); type = "DOI"; }
				if (id == null || type == null) { continue; }
				try {
					out.add(
						Builder
							.recordReference(IdType.valueOf(type.toUpperCase()), id)
					);
				} catch (IllegalArgumentException e) {
					log.warn("Unknown id type: {}", type);
				}
			}
		}

		return out;
	}

	private static ArrayNode recordsJson(
			ObjectMapper m, BiblioFacade f, Iterable<? extends Record> records
	) {
		var out = m.createArrayNode();
		records.forEach(r -> out.add(recordJson(m, f, r)));
		return out;
	}

	// public so the offline tests can assert the agent-visible shape
	@Deprecated
	public static ObjectNode recordJson(
			ObjectMapper m, BiblioFacade f, Record r
	) {
		var n = m.createObjectNode();
		r.getIdentifier()
			.ifPresent(v -> n.put("id", v));
		n.put(
			"idType",
			r.getIdentifierType()
				.toString()
		);
		r.getTitle()
			.ifPresent(v -> n.put("title", v));
		r.getAbstract()
			.ifPresent(v -> n.put("abstract", v));
		r.getJournal()
			.ifPresent(v -> n.put("journal", v));
		r.getDate()
			.ifPresent(v -> n.put("date", v.toString()));
		r.getPdfUri()
			.ifPresent(v -> n.put("url", v.toString()));
		r.getIdentifier(IdType.PMID)
			.ifPresent(v -> n.put("PMID", v));
		r.getIdentifier(IdType.DOI)
			.ifPresent(v -> n.put("DOI", v));
		r.getIdentifier(IdType.PMCID)
			.ifPresent(v -> n.put("PMCID", v));
		r.getIdentifier(IdType.ISBN)
			.ifPresent(v -> n.put("ISBN", v));
		r.getPublicationType()
			.ifPresent(v -> n.put("publicationType", v));
		r.getPublisher()
			.ifPresent(v -> n.put("publisher", v));

		var pn = n.putObject("provenance");
		var p = r.getProvenance();
		pn.put("source", p.getSource());
		p.getNativeType()
			.ifPresent(t -> pn.put("nativeType", t));
		if (p.isCurated()) { pn.put("curated", true); }

		if (f != null && f.citationStyle != null) {
			r.render(f.citationStyle)
				.ifPresent(v -> n.put("citation", v));
		}
		return n;
	}

	private static ArrayNode citationLinksJson(
			ObjectMapper m, BiblioFacade f, Iterable<? extends CitationLink> links
	) {
		var out = m.createArrayNode();
		links.forEach(l -> out.add(citationLinkJson(m, f, l)));
		return out;
	}

	private static ObjectNode citationLinkJson(
			ObjectMapper m, BiblioFacade f, CitationLink l
	) {
		var n = m.createObjectNode();
		l.getSource()
			.getIdentifier()
			.flatMap(RecordReference::getIdentifier)
			.ifPresent(v -> n.put("sourceId", v));
		l.getSource()
			.getIdentifier()
			.map(RecordReference::getIdentifierType)
			.ifPresent(v -> n.put("sourceIdType", v.toString()));
		l.getSource()
			.getTitle()
			.ifPresent(v -> n.put("sourceTitle", v));
		l.getTarget()
			.getIdentifier()
			.flatMap(RecordReference::getIdentifier)
			.ifPresent(v -> n.put("targetId", v));
		l.getTarget()
			.getIdentifier()
			.map(RecordReference::getIdentifierType)
			.ifPresent(v -> n.put("targetIdType", v.toString()));
		l.getTarget()
			.getTitle()
			.ifPresent(v -> n.put("targetTitle", v));
		l.getIndex()
			.ifPresent(v -> n.put("citationIndex", v));
		return n;
	}

	private static ArrayNode mappingsJson(
			ObjectMapper m,
			Iterable<? extends uk.co.terminological.bibliography.record.RecordReferenceMapping> mappings
	) {
		var out = m.createArrayNode();
		mappings.forEach(mp -> {
			var n = m.createObjectNode();
			mp.getSource()
				.getIdentifier()
				.ifPresent(v -> n.put("sourceId", v));
			n.put(
				"sourceIdType",
				mp.getSource()
					.getIdentifierType()
					.toString()
			);
			mp.getTarget()
				.getIdentifier()
				.ifPresent(v -> n.put("targetId", v));
			n.put(
				"targetIdType",
				mp.getTarget()
					.getIdentifierType()
					.toString()
			);
			out.add(n);
		});
		return out;
	}

	@Deprecated
	public static List<Tool> all(State state) {
		var m = state.mapper;
		var facade = state.facade;
		List<Tool> out = new ArrayList<>();

		// ---- Core biblio (stateless per call) ----

		out.add(
			new Tool(
					"search",
					"Search bibliographic APIs (crossref, entrez/pubmed, europepmc) for a query string.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},"
								+ "\"from\":{\"type\":\"string\",\"description\":\"earliest date YYYY-MM-DD\"},"
								+ "\"to\":{\"type\":\"string\",\"description\":\"latest date YYYY-MM-DD\"},"
								+ "\"limit\":{\"type\":\"integer\"},"
								+ "\"apis\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"crossref\",\"entrez\",\"europepmc\",\"medrxiv\"]}}},"
								+ "\"required\":[\"query\"]}"
					), args -> {
						optStringList(args, "apis").ifPresent(facade::withSearchApis);
						optInt(args, "limit").ifPresent(facade::withSearchLimit);
						return recordsJson(
							m,
							facade,
							facade.search(
								ImmutableQuery.builder()
									.allFields(
										args.get("query")
											.asText()
									)
									.after(optDate(args, "from"))
									.until(optDate(args, "to"))
									.limit(optInt(args, "limit"))
									.build()

							)
						);
					}
			)
		);

		out.add(
			new Tool(
					"fetch",
					"Fetch full record details for a list of ids (idType: DOI, PMID, PMCID, ...).",
					schema(m, IDS_SCHEMA),
					args -> recordsJson(
						m,
						facade,
						facade.fetch(ids(args))
							.values()
					)
			)
		);

		out.add(
			new Tool(
					"cites",
					"Get references cited by the given articles (citation graph pointing down).",
					schema(m, IDS_SCHEMA),
					args -> citationLinksJson(
						m,
						facade,
						facade.citesReferences(ids(args))
					)
			)
		);

		out.add(
			new Tool(
					"cited_by",
					"Get articles citing the given references (citation graph pointing up).",
					schema(m, IDS_SCHEMA),
					args -> citationLinksJson(
						m,
						facade,
						facade.referencesCiting(ids(args))
					)
			)
		);

		out.add(
			new Tool(
					"map_ids",
					"Map a list of ids to alternate id types (e.g. PMID to DOI) using id mapping APIs.",
					schema(m, IDS_SCHEMA),
					args -> mappingsJson(m, facade.mappings(ids(args)))
			)
		);

		out.add(
			new Tool(
					"references_from_pdf",
					"Download a pdf, extract its reference list and match the references to crossref works.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}"
					), args -> {
						try {
							var outJson = m.createArrayNode();
							for (CrossRefWork w : facade.getReferencesFromPdf(
								args.get("url")
									.asText()
							)) {
								outJson.add(recordJson(m, facade, w));
							}
							return outJson;
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
			)
		);

		out.add(
			new Tool(
					"citation_styles",
					"List supported citation styles for rendering references.",
					emptySchema(m), args -> {
						var outJson = m.createArrayNode();
						facade.getSupportedCitationStyles()
							.forEach(outJson::add);
						return outJson;
					}
			)
		);

		// ---- Session state ----

		out.add(
			new Tool(
					"session_status",
					"Show status of the current session repository (records, ids, citation links).",
					emptySchema(m), args -> state.session.get()
						.status()
			)
		);

		out.add(
			new Tool(
					"session_records",
					"List all records currently in the session repository.",
					emptySchema(m), args -> {
						var outJson = m.createArrayNode();
						state.session.get().records.forEach((uuid, r) -> {
							var n = recordJson(m, facade, r);
							n.put("uuid", uuid.toString());
							outJson.add(n);
						});
						return outJson;
					}
			)
		);

		out.add(
			new Tool(
					"session_citations",
					"List all citation links currently in the session repository.",
					emptySchema(m), args -> {
						var outJson = m.createArrayNode();
						var session = state.session.get();
						session.uuidCitations()
							.collect(Collectors.toList())
							.forEach(t -> outJson.add(uuidTupleJson(m, session, t)));
						return outJson;
					}
			)
		);

		out.add(
			new Tool(
					"collect_search",
					"Search and collect the results into the session repository.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"
					), args -> {
						state.session.get()
							.collectSearch(
								ImmutableQuery.builder()
									.allFields(
										args.get("query")
											.asText()
									)
									.build()
							);
						return state.session.get()
							.status();
					}
			)
		);

		out.add(
			new Tool(
					"collect_search_by_api",
					"Search individual APIs (e.g. {\"crossref\":\"query\",\"entrez\":\"query\"}) and collect results into the session repository.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"searchByApi\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}},\"required\":[\"searchByApi\"]}"
					), args -> {
						var byApi = new HashMap<String, Query>();
						args.get("searchByApi")
							.fields()
							.forEachRemaining(
								e -> byApi.put(
									e.getKey(),
									ImmutableQuery.builder()
										.allFields(
											e.getValue()
												.asText()
										)
										.build()
								)
							);
						state.session.get()
							.collectSearchByApi(byApi);
						return state.session.get()
							.status();
					}
			)
		);

		out.add(
			new Tool(
					"fetch_missing",
					"Fetch full record details for all session ids that do not yet have details.",
					emptySchema(m), args -> {
						state.session.get()
							.fetchMissingRecords();
						return state.session.get()
							.status();
					}
			)
		);

		out.add(
			new Tool(
					"expand_citation_graph",
					"Expand the citation graph up (articles citing) or down (references cited) for all retrieved session records.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"direction\":{\"type\":\"string\",\"enum\":[\"up\",\"down\"]},"
								+ "\"limit\":{\"type\":\"integer\"}},\"required\":[\"direction\"]}"
					), args -> {
						var session = state.session.get();
						int limit = optInt(args, "limit").orElse(1000);
						if (args.get("direction")
							.asText()
							.equals("up")) {
							session.expandUpCitationGraph(limit);
						} else {
							session.expandDownCitationGraph(limit);
						}
						return session.status();
					}
			)
		);

		out.add(
			new Tool(
					"add_records",
					"Add records for a list of ids to the session repository.",
					schema(m, IDS_SCHEMA), args -> {
						state.session.get()
							.fetchRecords(ids(args));
						return state.session.get()
							.status();
					}
			)
		);

		out.add(
			new Tool(
					"remove_records",
					"Remove records for a list of ids from the session repository.",
					schema(m, IDS_SCHEMA), args -> {
						state.session.get()
							.removeRecords(ids(args));
						return state.session.get()
							.status();
					}
			)
		);

		out.add(
			new Tool(
					"find_alternate_ids",
					"Find alternate ids (DOI/PMID/PMCID mappings) for all records in the session repository.",
					emptySchema(m), args -> {
						state.session.get()
							.findAlternateIds();
						return state.session.get()
							.status();
					}
			)
		);

		out.add(
			new Tool(
					"save_repository", "Save the session repository to a file.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"
					), args -> {
						try {
							state.session.get()
								.save(
									args.get("path")
										.asText()
								);
							return state.session.get()
								.status();
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
			)
		);

		out.add(
			new Tool(
					"load_repository",
					"Load a session repository from a file, replacing the current session.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"
					), args -> {
						try {
							state.session.set(
								facade.loadSession(
									args.get("path")
										.asText()
								)
							);
							return state.session.get()
								.status();
						} catch (IOException | ClassNotFoundException e) {
							throw new RuntimeException(e);
						}
					}
			)
		);

		// ---- Analysis ----

		out.add(
			new Tool(
					"page_rank",
					"Page rank over the session citation graph, joined with record titles.",
					emptySchema(m),
					args -> Analysis.pageRankWithTitles(state.session.get())
			)
		);

		out.add(
			new Tool(
					"topic_model",
					"Topic model (LDA) over the session records (title + abstract). Returns topic, uuid and score for each document-topic weight.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"stopwords\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
								+ "\"topics\":{\"type\":\"integer\"},\"alpha\":{\"type\":\"number\"},\"beta\":{\"type\":\"number\"}}}"
					),
					args -> Analysis.topicModel(
						state.session.get(),
						optStringList(args, "stopwords").orElse(List.of()),
						optInt(args, "topics").orElse(10),
						args.has("alpha") ? args.get("alpha")
							.asDouble() : 0.10,
						args.has("beta") ? args.get("beta")
							.asDouble() : 0.1
					)
			)
		);

		// ---- Zotero: the curated library ------------------------------------------

		out.add(
			new Tool(
					"zotero_status",
					"State of the Zotero bridge: whether the local API is reachable, the library identity and version, and whether writes are enabled and where.",
					emptySchema(m), args -> {
						var status = m.createObjectNode();
						var zotero = state.zotero;
						if (zotero == null) { status
							.put("enabled", false); return status; }
						status.put(
							"enabled",
							zotero.getConfig()
								.isEnabled()
						);
						status.put(
							"baseUrl",
							zotero.getConfig()
								.getBaseUrl()
						);
						var reachable = zotero.isReachable();
						status.put("reachable", reachable);
						if (reachable) {
							status.put("serverId", zotero.getServerId());
							status.put("apiVersion", zotero.getApiVersion());
							status.put("schemaVersion", zotero.getSchemaVersion());
							// read before reporting the version: the header arrives with the data
							status.put(
								"items",
								zotero.itemsTop()
									.size()
							);
							status.put(
								"collections",
								zotero.collections()
									.size()
							);
							status
								.put("libraryVersion", zotero.getLastLibraryVersion());
						}
						status.put(
							"writesEnabled",
							zotero.getConfig()
								.isAllowWrites()
						);
						status.put(
							"writeCollection",
							zotero.getConfig()
								.getWriteCollection()
						);
						status
							.put("writeAuthorisation", zotero.getWriteAuthorisation());
						var index = state.libraryIndex.get();
						if (index != null) {
							status.put("indexSize", index.size());
							status
								.put("indexLibraryVersion", index.getLibraryVersion());
						}
						return status;
					}
			)
		);

		out.add(
			new Tool(
					"zotero_collections",
					"List the collections in the Zotero library (folders; sub-collections have a parent key).",
					emptySchema(m), args -> {
						var zotero = state.requireZotero();
						var outJson = m.createArrayNode();
						for (ZoteroCollection collection : zotero.collections()) {
							var c = outJson.addObject();
							c.put("key", collection.getKey());
							c.put("name", collection.getName());
							if (collection.getParentCollection() != null) {
								c.put(
									"parentCollection",
									collection.getParentCollection()
								);
							}
						}
						return outJson;
					}
			)
		);

		out.add(
			new Tool(
					"zotero_import",
					"Read the user's curated library into the session: a collection, a search, or the most recently modified items. This is the entry point for a snowball search — import the seed collection, then expand and rank it with the other tools.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"collection\":{\"type\":\"string\",\"description\":\"collection key from zotero_collections\"},\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}}}"
					), args -> {
						var zotero = state.requireZotero();
						List<ZoteroItem> items;
						if (args.has("collection")) {
							items = zotero.itemsInCollection(
								args.get("collection")
									.asText()
							);
						} else if (args.has("query")) {
							items = zotero.search(
								args.get("query")
									.asText()
							);
						} else {
							items = zotero.itemsTop();
						}
						int limit = optInt(args, "limit").orElse(Integer.MAX_VALUE);
						var imported = 0;
						for (ZoteroItem item : items) {
							if (imported >= limit) { break; }
							state.session.get()
								.add(item);
							imported++;
						}
						return state.session.get()
							.status();
					}
			)
		);

		out.add(
			new Tool(
					"zotero_check",
					"Which of these records the library already holds. Uses DOI, then ISBN, then a normalised title+year match, so an item with no identifier at all can still be recognised. Defaults to the session's records.",
					schema(m, IDS_SCHEMA), args -> {
						var index = state.requireIndex();
						var records = ids(args).isEmpty() ? state.session.get()
							.getRecords()
							.values()
							: state.facade.fetch(ids(args))
								.values();
						var outJson = m.createArrayNode();
						for (Record record : records) {
							var row = outJson.addObject();
							record.getIdentifier()
								.ifPresent(v -> row.put("id", v));
							record.getTitle()
								.ifPresent(v -> row.put("title", v));
							var key = index.findKey(record);
							row.put("inLibrary", key.isPresent());
							key.ifPresent(v -> row.put("zoteroKey", v));
						}
						return outJson;
					}
			)
		);

		out.add(
			new Tool(
					"zotero_publish",
					"Create a sub-collection of the configured write target and add the session's records that the library does not already hold. Nothing existing is modified: where the API data disagrees with an item already held the item is skipped, so the agent can decide between updating and duplicating. At most 25 items are created per call unless limit is given; anything Zotero refuses is logged.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"subcollection\":{\"type\":\"string\",\"description\":\"name for the new sub-collection\"},"
								+ "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
								+ "\"limit\":{\"type\":\"integer\",\"description\":\"most items to create in one call (default 25)\"},"
								+ "\"includeHeld\":{\"type\":\"boolean\",\"description\":\"create items the library already holds (the duplicate route)\"}},"
								+ "\"required\":[\"subcollection\"]}"
					), args -> {
						var zotero = state.requireZotero();
						zotero.checkWritesAllowed();
						var index = state.requireIndex();
						var tags = optStringList(args, "tags").orElse(List.of());
						// a publish can create a lot of items in someone real library, so it stops
						// at a modest number and reports what it left behind
						int limit = optInt(args, "limit").orElse(25);

						// select first: nothing is created in the library until we know there is
						// something to write
						List<Record> selected = new ArrayList<>();
						var skipped = m.createArrayNode();
						var alreadyHeld = 0;
						var overLimit = 0;
						var includeHeld = args.path("includeHeld")
							.asBoolean(false);
						for (Record record : state.session.get()
							.getRecords()
							.values()) {
							var existing = index.findKey(record);
							// includeHeld is the duplicate route: the agent has decided the
							// disagreement is best left for the user to resolve in Zotero
							if (existing.isPresent() && !includeHeld) {
								alreadyHeld++;
								continue;
							}
							if (selected.size() >= limit) { overLimit++; continue; }
							try {
								ZoteroItemWriter.toItem(record, null, tags); // refuses an item without a title
								selected.add(record);
							} catch (IllegalArgumentException e) {
								var row = skipped.addObject();
								record.getTitle()
									.ifPresent(v -> row.put("title", v));
								row.put("reason", e.getMessage());
							}
						}

						String target = null;
						var created = m.createArrayNode();
						var createdCount = 0;
						if (!selected.isEmpty()) {
							target = zotero.createSubCollection(
								args.get("subcollection")
									.asText()
							);
							List<com.fasterxml.jackson.databind.JsonNode> items = new ArrayList<>();
							for (Record record : selected) {
								items
									.add(ZoteroItemWriter.toItem(record, target, tags));
								record.getTitle()
									.ifPresent(created::add);
							}
							createdCount = zotero.createItems(items, target).created
								.size();
							state.libraryIndex.set(null); // the library changed
						}

						var result = m.createObjectNode();
						result.put(
							"collection",
							target == null ? "(nothing to publish)" : target
						);
						result.put("submittedCount", selected.size());
						result.put("createdCount", createdCount);
						result.set("submitted", created);
						result.put("alreadyInLibrary", alreadyHeld);
						result.put("leftForANextCall", overLimit);
						result.set("skipped", skipped);
						return result;
					}
			)
		);

		out.add(
			new Tool(
					"zotero_cleanup_report",
					"Compare every item in a Zotero collection against what the bibliographic APIs say, field by field. Reports agreements, gaps the APIs can fill, conflicts where both sides have a value, and items it could not check at all. A field whose Zotero value already holds markup is flagged with zoteroHasMarkup, with the cleaned value offered, so a marked-up abstract can be replaced rather than left looking like an agreement. It never picks a winner: the agent decides what to change, in conversation with the user, and then calls zotero_apply_updates. Read-only.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"collection\":{\"type\":\"string\",\"description\":\"collection key from zotero_collections\"},"
								+ "\"limit\":{\"type\":\"integer\",\"description\":\"most items to compare (default 25)\"}},\"required\":[\"collection\"]}"
					), args -> {
						var zotero = state.requireZotero();
						var items = zotero.itemsInCollection(
							args.get("collection")
								.asText()
						);
						int limit = optInt(args, "limit").orElse(25);
						var outJson = m.createArrayNode();
						var reported = 0;
						var leftForANextCall = 0;
						for (ZoteroItem item : items) {
							if (reported >= limit) { leftForANextCall++; continue; }
							reported++;
							var row = outJson.addObject();
							row.put("itemKey", item.getKey());
							item.getTitle()
								.ifPresent(t -> row.put("title", t));

							// fetch by identifier only: a title search would be a guess, and a
							// guessed comparison is worse than none
							Set<ImmutableRecordReference> ids = new java.util.LinkedHashSet<>();
							item.getIdentifier(IdType.DOI)
								.ifPresent(
									v -> ids.add(Builder.recordReference(IdType.DOI, v))
								);
							item.getIdentifier(IdType.ISBN)
								.ifPresent(
									v -> ids.add(Builder.recordReference(IdType.ISBN, v))
								);
							if (ids.isEmpty()) { row
								.put("status", "no identifier"); continue; }
							var bySource = state.facade.fetchBySource(ids);
							List<Record> apiRecords = new ArrayList<>();
							bySource.values()
								.forEach(apiRecords::addAll);
							if (apiRecords.isEmpty()) {
								row.put("status", "no API record");
								continue;
							}
							var comparison = MetadataComparison
								.compare(m, item.getData(), apiRecords);
							row.put(
								"status",
								comparison.get("classification")
									.asText()
							);
							row.set("comparison", comparison);
							var sources = row.putArray("sources");
							bySource.keySet()
								.forEach(sources::add);
						}
						var result = m.createObjectNode();
						result.put(
							"collection",
							args.get("collection")
								.asText()
						);
						result.put("considered", items.size());
						result.put("reported", reported);
						result.put("leftForANextCall", leftForANextCall);
						result.set("items", outJson);
						return result;
					}
			)
		);

		out.add(
			new Tool(
					"zotero_apply_updates",
					"Apply explicit field changes to existing Zotero items, overwriting those fields only. Use this after zotero_cleanup_report, once the user has agreed the conflicting changes (the agent should not need to ask about straightforward gaps). Only the fields named are touched; the values they replaced are reported back. To add a duplicate instead, use zotero_publish with includeHeld.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"updates\":{\"type\":\"array\",\"items\":{\"type\":\"object\","
								+ "\"properties\":{\"itemKey\":{\"type\":\"string\"},\"fields\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}},"
								+ "\"required\":[\"itemKey\",\"fields\"]}}},\"required\":[\"updates\"]}"
					), args -> {
						var zotero = state.requireZotero();
						zotero.checkWritesAllowed();
						Map<String, Map<String, String>> fieldsByItemKey = new java.util.LinkedHashMap<>();
						for (JsonNode update : args.get("updates")) {
							Map<String, String> fields = new java.util.LinkedHashMap<>();
							update.path("fields")
								.fields()
								.forEachRemaining(
									entry -> fields.put(
										entry.getKey(),
										entry.getValue()
											.asText()
									)
								);
							fieldsByItemKey.put(
								update.path("itemKey")
									.asText(),
								fields
							);
						}
						var written = zotero.updateItems(fieldsByItemKey);
						var result = m.createObjectNode();
						result.put("updatedCount", written.created.size());
						result.set("updated", m.valueToTree(written.created));
						var changes = result.putArray("changes");
						written.previous.forEach((itemKey, previous) -> {
							var row = changes.addObject();
							row.put("itemKey", itemKey);
							var before = row.putObject("previous");
							previous.forEach(
								(field, value) -> before
									.put(field, value == null ? "" : value)
							);
							var after = row.putObject("applied");
							fieldsByItemKey.get(itemKey)
								.forEach(after::put);
						});
						if (written.hasFailures()) {
							result.set("failed", m.valueToTree(written.failures));
						}
						return result;
					}
			)
		);

		out.add(
			new Tool(
					"zotero_tag",
					"Add tags to Zotero items, keeping the tags they already have. Only items inside the configured write collection may be tagged. Use this to record decisions (why an item was added, what it relates to). All the items are tagged in one request, because a Zotero write authorisation is usually single-use and each request is a permission prompt.",
					schema(
						m,
						"{\"type\":\"object\",\"properties\":{\"itemKeys\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
								+ "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"itemKeys\",\"tags\"]}"
					), args -> {
						var zotero = state.requireZotero();
						var tags = optStringList(args, "tags").orElse(List.of());
						List<String> itemKeys = new ArrayList<>();
						args.get("itemKeys")
							.forEach(key -> itemKeys.add(key.asText()));
						var written = zotero.addTags(itemKeys, tags);
						var result = m.createObjectNode();
						result.put("taggedCount", written.created.size());
						result.set("tagged", m.valueToTree(written.created));
						if (written.hasFailures()) {
							result.set("failed", m.valueToTree(written.failures));
						}
						return result;
					}
			)
		);

		return out;
	}

	private static ObjectNode uuidTupleJson(
			ObjectMapper m, Session session, Tuple<UUID, UUID> t
	) {
		var n = m.createObjectNode();
		n.put(
			"sourceUuid",
			t.getFirst()
				.toString()
		);
		n.put(
			"targetUuid",
			t.getSecond()
				.toString()
		);
		session.records.getOpt(t.getFirst())
			.flatMap(Record::getTitle)
			.ifPresent(v -> n.put("sourceTitle", v));
		session.records.getOpt(t.getSecond())
			.flatMap(Record::getTitle)
			.ifPresent(v -> n.put("targetTitle", v));
		return n;
	}

}
