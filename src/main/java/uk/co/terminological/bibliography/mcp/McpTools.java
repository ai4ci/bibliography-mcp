package uk.co.terminological.bibliography.mcp;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.terminological.bibliography.crossref.CrossRefWork;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.datatypes.Tuple;

/**
 * MCP tool definitions over the bibliographic facade and session. Each tool
 * takes an ObjectNode of arguments and returns a Jackson node / list for
 * serialisation into the tool response.
 */
public class McpTools {

	static Logger log = LoggerFactory.getLogger(McpTools.class);

	/** Server state shared by all tool handlers; session is swappable via load. */
	public static class State {
		public final BiblioFacade facade;
		public final ObjectMapper mapper;
		public final AtomicReference<Session> session;

		public State(BiblioFacade facade, ObjectMapper mapper) {
			this.facade = facade;
			this.mapper = mapper;
			this.session = new AtomicReference<>(facade.getSession());
		}
	}

	public static class Tool {
		public final String name;
		public final String description;
		public final ObjectNode inputSchema;
		public final Function<ObjectNode, Object> handler;

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

	private static final String IDS_SCHEMA =
			"{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":\"array\",\"items\":"
			+ "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"idType\":{\"type\":\"string\"}},"
			+ "\"required\":[\"id\",\"idType\"]}}},\"required\":[\"ids\"]}";

	private static Optional<LocalDate> optDate(ObjectNode args, String key) {
		JsonNode n = args.get(key);
		if (n == null || n.isNull()) return Optional.empty();
		return Optional.of(LocalDate.parse(n.asText()));
	}

	private static Optional<Integer> optInt(ObjectNode args, String key) {
		JsonNode n = args.get(key);
		if (n == null || n.isNull()) return Optional.empty();
		return Optional.of(n.asInt());
	}

	private static Optional<List<String>> optStringList(ObjectNode args, String key) {
		JsonNode n = args.get(key);
		if (n == null || n.isNull() || !n.isArray()) return Optional.empty();
		List<String> out = new ArrayList<>();
		n.forEach(x -> out.add(x.asText()));
		return Optional.of(out);
	}

	private static Set<ImmutableRecordReference> ids(ObjectNode args) {
		Set<ImmutableRecordReference> out = new HashSet<>();
		JsonNode arr = args.get("ids");
		if (arr != null && arr.isArray()) {
			for (JsonNode n : arr) {
				String id = n.has("id") ? n.get("id")
					.asText() : null;
				String type = n.has("idType") ? n.get("idType")
					.asText() : null;
				if (n.isTextual()) {
					id = n.asText();
					type = "DOI";
				}
				if (id == null || type == null) continue;
				try {
					out.add(Builder.recordReference(IdType.valueOf(type.toUpperCase()), id));
				} catch (IllegalArgumentException e) {
					log.warn("Unknown id type: {}", type);
				}
			}
		}
		return out;
	}

	private static ArrayNode recordsJson(ObjectMapper m, BiblioFacade f, Iterable<? extends Record> records) {
		ArrayNode out = m.createArrayNode();
		records.forEach(r -> out.add(recordJson(m, f, r)));
		return out;
	}

	private static ObjectNode recordJson(ObjectMapper m, BiblioFacade f, Record r) {
		ObjectNode n = m.createObjectNode();
		r.getIdentifier().ifPresent(v -> n.put("id", v));
		n.put("idType", r.getIdentifierType().toString());
		r.getTitle().ifPresent(v -> n.put("title", v));
		r.getAbstract().ifPresent(v -> n.put("abstract", v));
		r.getJournal().ifPresent(v -> n.put("journal", v));
		r.getDate().ifPresent(v -> n.put("date", v.toString()));
		r.getPdfUri().ifPresent(v -> n.put("url", v.toString()));
		r.getIdentifier(IdType.PMID).ifPresent(v -> n.put("PMID", v));
		r.getIdentifier(IdType.DOI).ifPresent(v -> n.put("DOI", v));
		r.getIdentifier(IdType.PMCID).ifPresent(v -> n.put("PMCID", v));
		if (f != null && f.citationStyle != null)
			r.render(f.citationStyle).ifPresent(v -> n.put("citation", v));
		return n;
	}

	private static ArrayNode citationLinksJson(ObjectMapper m, BiblioFacade f, Iterable<? extends CitationLink> links) {
		ArrayNode out = m.createArrayNode();
		links.forEach(l -> out.add(citationLinkJson(m, f, l)));
		return out;
	}

	private static ObjectNode citationLinkJson(ObjectMapper m, BiblioFacade f, CitationLink l) {
		ObjectNode n = m.createObjectNode();
		l.getSource().getIdentifier().flatMap(RecordReference::getIdentifier).ifPresent(v -> n.put("sourceId", v));
		l.getSource().getIdentifier().map(RecordReference::getIdentifierType).ifPresent(v -> n.put("sourceIdType", v.toString()));
		l.getSource().getTitle().ifPresent(v -> n.put("sourceTitle", v));
		l.getTarget().getIdentifier().flatMap(RecordReference::getIdentifier).ifPresent(v -> n.put("targetId", v));
		l.getTarget().getIdentifier().map(RecordReference::getIdentifierType).ifPresent(v -> n.put("targetIdType", v.toString()));
		l.getTarget().getTitle().ifPresent(v -> n.put("targetTitle", v));
		l.getIndex().ifPresent(v -> n.put("citationIndex", v));
		return n;
	}

	private static ArrayNode mappingsJson(ObjectMapper m, Iterable<? extends uk.co.terminological.bibliography.record.RecordReferenceMapping> mappings) {
		ArrayNode out = m.createArrayNode();
		mappings.forEach(mp -> {
			ObjectNode n = m.createObjectNode();
			mp.getSource().getIdentifier().ifPresent(v -> n.put("sourceId", v));
			n.put("sourceIdType", mp.getSource().getIdentifierType().toString());
			mp.getTarget().getIdentifier().ifPresent(v -> n.put("targetId", v));
			n.put("targetIdType", mp.getTarget().getIdentifierType().toString());
			out.add(n);
		});
		return out;
	}

	public static List<Tool> all(State state) {
		ObjectMapper m = state.mapper;
		BiblioFacade facade = state.facade;
		List<Tool> out = new ArrayList<>();

		// ---- Core biblio (stateless per call) ----

		out.add(new Tool(
			"search",
			"Search bibliographic APIs (crossref, entrez/pubmed, europepmc) for a query string.",
			schema(m, "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},"
					+ "\"from\":{\"type\":\"string\",\"description\":\"earliest date YYYY-MM-DD\"},"
					+ "\"to\":{\"type\":\"string\",\"description\":\"latest date YYYY-MM-DD\"},"
					+ "\"limit\":{\"type\":\"integer\"},"
					+ "\"apis\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"crossref\",\"entrez\",\"europepmc\"]}}},"
					+ "\"required\":[\"query\"]}"),
			args -> {
				optStringList(args, "apis").ifPresent(facade::withSearchApis);
				if (args.has("from")) facade.withSearchDates(args.get("from").asText(), args.has("to") ? args.get("to").asText() : null);
				optInt(args, "limit").ifPresent(facade::withSearchLimit);
				return recordsJson(m, facade, facade.search(
					args.get("query").asText(),
					optDate(args, "from"),
					optDate(args, "to"),
					optInt(args, "limit")));
			}
		));

		out.add(new Tool(
			"fetch",
			"Fetch full record details for a list of ids (idType: DOI, PMID, PMCID, ...).",
			schema(m, IDS_SCHEMA),
			args -> recordsJson(m, facade, facade.fetch(ids(args)).values())
		));

		out.add(new Tool(
			"cites",
			"Get references cited by the given articles (citation graph pointing down).",
			schema(m, IDS_SCHEMA),
			args -> citationLinksJson(m, facade, facade.citesReferences(ids(args)))
		));

		out.add(new Tool(
			"cited_by",
			"Get articles citing the given references (citation graph pointing up).",
			schema(m, IDS_SCHEMA),
			args -> citationLinksJson(m, facade, facade.referencesCiting(ids(args)))
		));

		out.add(new Tool(
			"map_ids",
			"Map a list of ids to alternate id types (e.g. PMID to DOI) using id mapping APIs.",
			schema(m, IDS_SCHEMA),
			args -> mappingsJson(m, facade.mappings(ids(args)))
		));

		out.add(new Tool(
			"references_from_pdf",
			"Download a pdf, extract its reference list and match the references to crossref works.",
			schema(m, "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}"),
			args -> {
				try {
					ArrayNode outJson = m.createArrayNode();
					for (CrossRefWork w : facade.getReferencesFromPdf(args.get("url").asText()))
						outJson.add(recordJson(m, facade, w));
					return outJson;
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		));

		out.add(new Tool(
			"citation_styles",
			"List supported citation styles for rendering references.",
			emptySchema(m),
			args -> {
				ArrayNode outJson = m.createArrayNode();
				facade.getSupportedCitationStyles().forEach(outJson::add);
				return outJson;
			}
		));

		// ---- Session state ----

		out.add(new Tool(
			"session_status",
			"Show status of the current session repository (records, ids, citation links).",
			emptySchema(m),
			args -> state.session.get().status()
		));

		out.add(new Tool(
			"session_records",
			"List all records currently in the session repository.",
			emptySchema(m),
			args -> {
				ArrayNode outJson = m.createArrayNode();
				state.session.get().records.forEach((uuid, r) -> {
					ObjectNode n = recordJson(m, facade, r);
					n.put("uuid", uuid.toString());
					outJson.add(n);
				});
				return outJson;
			}
		));

		out.add(new Tool(
			"session_citations",
			"List all citation links currently in the session repository.",
			emptySchema(m),
			args -> {
				ArrayNode outJson = m.createArrayNode();
				Session session = state.session.get();
				session.uuidCitations().collect(Collectors.toList()).forEach(t -> outJson.add(uuidTupleJson(m, session, t)));
				return outJson;
			}
		));

		out.add(new Tool(
			"collect_search",
			"Search and collect the results into the session repository.",
			schema(m, "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"),
			args -> {
				state.session.get().collectSearch(args.get("query").asText());
				return state.session.get().status();
			}
		));

		out.add(new Tool(
			"collect_search_by_api",
			"Search individual APIs (e.g. {\"crossref\":\"query\",\"entrez\":\"query\"}) and collect results into the session repository.",
			schema(m, "{\"type\":\"object\",\"properties\":{\"searchByApi\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}},\"required\":[\"searchByApi\"]}"),
			args -> {
				HashMap<String, String> byApi = new HashMap<>();
				args.get("searchByApi").fields().forEachRemaining(e -> byApi.put(e.getKey(), e.getValue().asText()));
				state.session.get().collectSearchByApi(byApi);
				return state.session.get().status();
			}
		));

		out.add(new Tool(
			"fetch_missing",
			"Fetch full record details for all session ids that do not yet have details.",
			emptySchema(m),
			args -> {
				state.session.get().fetchMissingRecords();
				return state.session.get().status();
			}
		));

		out.add(new Tool(
			"expand_citation_graph",
			"Expand the citation graph up (articles citing) or down (references cited) for all retrieved session records.",
			schema(m, "{\"type\":\"object\",\"properties\":{\"direction\":{\"type\":\"string\",\"enum\":[\"up\",\"down\"]},"
					+ "\"limit\":{\"type\":\"integer\"}},\"required\":[\"direction\"]}"),
			args -> {
				Session session = state.session.get();
				int limit = optInt(args, "limit").orElse(1000);
				if (args.get("direction").asText().equals("up")) session.expandUpCitationGraph(limit);
				else session.expandDownCitationGraph(limit);
				return session.status();
			}
		));

		out.add(new Tool(
			"add_records",
			"Add records for a list of ids to the session repository.",
			schema(m, IDS_SCHEMA),
			args -> {
				state.session.get().fetchRecords(ids(args));
				return state.session.get().status();
			}
		));

		out.add(new Tool(
			"remove_records",
			"Remove records for a list of ids from the session repository.",
			schema(m, IDS_SCHEMA),
			args -> {
				state.session.get().removeRecords(ids(args));
				return state.session.get().status();
			}
		));

		out.add(new Tool(
			"find_alternate_ids",
			"Find alternate ids (DOI/PMID/PMCID mappings) for all records in the session repository.",
			emptySchema(m),
			args -> {
				state.session.get().findAlternateIds();
				return state.session.get().status();
			}
		));

		out.add(new Tool(
			"save_repository",
			"Save the session repository to a file.",
			schema(m, "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"),
			args -> {
				try {
					state.session.get().save(args.get("path").asText());
					return state.session.get().status();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		));

		out.add(new Tool(
			"load_repository",
			"Load a session repository from a file, replacing the current session.",
			schema(m, "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"),
			args -> {
				try {
					state.session.set(facade.loadSession(args.get("path").asText()));
					return state.session.get().status();
				} catch (IOException | ClassNotFoundException e) {
					throw new RuntimeException(e);
				}
			}
		));

		// ---- Analysis ----

		out.add(new Tool(
			"page_rank",
			"Page rank over the session citation graph, joined with record titles.",
			emptySchema(m),
			args -> Analysis.pageRankWithTitles(state.session.get())
		));

		out.add(new Tool(
			"topic_model",
			"Topic model (LDA) over the session records (title + abstract). Returns topic, uuid and score for each document-topic weight.",
			schema(m, "{\"type\":\"object\",\"properties\":{\"stopwords\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
					+ "\"topics\":{\"type\":\"integer\"},\"alpha\":{\"type\":\"number\"},\"beta\":{\"type\":\"number\"}}}"),
			args -> Analysis.topicModel(
				state.session.get(),
				optStringList(args, "stopwords").orElse(List.of()),
				optInt(args, "topics").orElse(10),
				args.has("alpha") ? args.get("alpha").asDouble() : 0.10,
				args.has("beta") ? args.get("beta").asDouble() : 0.1)
		));

		return out;
	}

	private static ObjectNode uuidTupleJson(ObjectMapper m, Session session, Tuple<UUID, UUID> t) {
		ObjectNode n = m.createObjectNode();
		n.put("sourceUuid", t.getFirst().toString());
		n.put("targetUuid", t.getSecond().toString());
		session.records.getOpt(t.getFirst()).flatMap(Record::getTitle).ifPresent(v -> n.put("sourceTitle", v));
		session.records.getOpt(t.getSecond()).flatMap(Record::getTitle).ifPresent(v -> n.put("targetTitle", v));
		return n;
	}

}
