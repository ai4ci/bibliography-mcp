package uk.co.terminological.bibliography.mcp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import uk.co.terminological.bibliography.api.BiblioFacade;
import uk.co.terminological.bibliography.api.zotero.ZoteroItemWriter;
import uk.co.terminological.bibliography.client.ImmutableQuery;
import uk.co.terminological.bibliography.crawl.CrawlJob;
import uk.co.terminological.bibliography.crawl.Direction;
import uk.co.terminological.bibliography.repository.Ranking;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Record;

/**
 * The tools, which are workflow steps rather than library calls.
 *
 * Six, where there were thirty. The thirty were primitives, so an agent had to sequence a whole workflow itself —
 * collect, then fetch what is missing, then map identifiers, then expand up, then down, then rank — and a mistake in
 * the ordering was invisible until the answer looked wrong. These are shaped so that each workflow is one or two
 * calls, and so that what a call returns carries the scores it was ordered by.
 *
 * The SDK validates arguments against each schema before a handler runs, so there is no argument checking here beyond
 * what the schema cannot say.
 */
// [IMPLEMENTS](/design/features/feat-010-crawl-engine-with-zotero-mirror.md)
// [IMPLEMENTS](/design/external-interfaces/interface-001-mcp-http-transport.md)
public class BiblioTools {

	private static final Logger log = LoggerFactory.getLogger(BiblioTools.class);

	private BiblioTools() {}

	public static List<SyncToolSpecification> all(
			BiblioContext ctx, McpJsonMapper json
	) {
		List<SyncToolSpecification> out = new ArrayList<>();
		out.add(search(ctx, json));
		out.add(listRecords(ctx, json));
		out.add(select(ctx, json));
		out.add(crawl(ctx, json));
		out.add(rank(ctx, json));
		out.add(zoteroSync(ctx, json));
		return out;
	}

	// ---- search -------------------------------------------------------------

	private static SyncToolSpecification search(
			BiblioContext ctx, McpJsonMapper json
	) {
		var tool = Tool.builder()
			.name("search")
			.description(
				"Search the bibliographic APIs ("
						+ String.join(", ", BiblioFacade.searchableApis())
						+ ") and return records ranked across them, each with the score that ordered it"
						+ " and the APIs that found it. A query that is nothing but an identifier (DOI, PMCID,"
						+ " pmid:NNNN, OpenAlex id) is looked up rather than text-matched."
						+ " Set collect to add the results to the repository."
			)
			.inputSchema(
				json,
				"{\"type\":\"object\",\"properties\":{"
						+ "\"query\":{\"type\":\"string\",\"description\":\"terms matched anywhere, or an identifier\"},"
						+ "\"title\":{\"type\":\"string\"},"
						+ "\"author\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
						+ "\"journal\":{\"type\":\"string\"},"
						+ "\"year\":{\"type\":\"integer\"},"
						+ "\"from\":{\"type\":\"string\",\"description\":\"earliest publication date YYYY-MM-DD\"},"
						+ "\"to\":{\"type\":\"string\",\"description\":\"latest publication date YYYY-MM-DD\"},"
						+ "\"apis\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":["
						+ BiblioFacade.searchableApis()
							.stream()
							.map(a -> "\"" + a + "\"")
							.collect(Collectors.joining(","))
						+ "]}},"
						+ "\"limit\":{\"type\":\"integer\"},"
						+ "\"collect\":{\"type\":\"boolean\",\"description\":\"add the results to the repository\"},"
						+ "\"style\":{\"type\":\"string\",\"description\":\"a CSL style name to render a citation with\"}"
						+ "}}"
			)
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(handler(ctx, (request, mapper) -> {
				var args = request.arguments();
				var query = ImmutableQuery.builder();
				string(args, "query").ifPresent(query::allFields);
				string(args, "title").ifPresent(query::titleAbstract);
				strings(args, "author").forEach(query::addAuthors);
				string(args, "journal").ifPresent(query::journal);
				integer(args, "year").ifPresent(query::year);
				string(args, "from").map(LocalDate::parse)
					.ifPresent(query::after);
				string(args, "to").map(LocalDate::parse)
					.ifPresent(query::until);
				integer(args, "limit").ifPresent(query::limit);

				var apis = strings(args, "apis");
				var facade = apis.isEmpty() ? ctx.facade()
					: ctx.facade()
						.withSearchApis(apis);

				var scored = facade.scoredSearch(query.build());
				boolean collect = bool(args, "collect").orElse(false);
				var style = string(args, "style").orElse(null);

				var out = mapper.createArrayNode();
				for (var hit : scored) {
					if (collect) {
						ctx.store()
							.putRecord(hit.record());
						ctx.store()
							.setScore(hit.record(), hit.score());
					}
					var node = RecordJson
						.of(mapper, hit.record(), style, hit.score());
					var matched = node.putArray("matchedApis");
					hit.matchedApis()
						.forEach(matched::add);
					out.add(node);
				}
				if (collect) log.info(
					"search collected {} records into the repository", scored.size()
				);
				return out.toString();
			}))
			.build();
	}

	// ---- list_records -------------------------------------------------------

	private static SyncToolSpecification listRecords(
			BiblioContext ctx, McpJsonMapper json
	) {
		var tool = Tool.builder()
			.name("list_records")
			.description(
				"Read the repository: everything, or only what is selected. Name a CSL style to have each"
						+ " record carry a rendered citation."
			)
			.inputSchema(
				json,
				"{\"type\":\"object\",\"properties\":{"
						+ "\"filter\":{\"type\":\"string\",\"enum\":[\"all\",\"selected\"],\"default\":\"all\"},"
						+ "\"top\":{\"type\":\"integer\",\"description\":\"at most this many, highest scoring first\"},"
						+ "\"style\":{\"type\":\"string\"}"
						+ "}}"
			)
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(handler(ctx, (request, mapper) -> {
				var args = request.arguments();
				var filter = string(args, "filter").orElse("all");
				var top = integer(args, "top");
				var style = string(args, "style").orElse(null);

				List<Record> records;
				if ("selected".equals(filter)) {
					records = ctx.store()
						.selected()
						.collect(Collectors.toList());
					if (top.isPresent()) records = records
						.subList(0, Math.min(records.size(), top.get()));
				} else if (top.isPresent()) {
					records = ctx.store()
						.topByScore(top.get())
						.collect(Collectors.toList());
					// a repository nothing has scored yet still has records in it
					if (records.isEmpty()) records = ctx.store()
						.records()
						.limit(top.get())
						.collect(Collectors.toList());
				} else {
					records = ctx.store()
						.records()
						.collect(Collectors.toList());
				}
				return RecordJson.all(mapper, records, style)
					.toString();
			}))
			.build();
	}

	// ---- select -------------------------------------------------------------

	private static SyncToolSpecification select(
			BiblioContext ctx, McpJsonMapper json
	) {
		var tool = Tool.builder()
			.name("select")
			.description(
				"Mark records as selected, or unmark them. A mark, never a delete: what is rejected stays in"
						+ " the citation graph so the metrics stay honest and the decision can be reversed."
			)
			.inputSchema(
				json,
				"{\"type\":\"object\",\"properties\":{"
						+ "\"ids\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
						+ "\"id\":{\"type\":\"string\"},\"idType\":{\"type\":\"string\"}},\"required\":[\"id\"]}},"
						+ "\"value\":{\"type\":\"boolean\",\"default\":true}"
						+ "},\"required\":[\"ids\"]}"
			)
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(handler(ctx, (request, mapper) -> {
				var args = request.arguments();
				boolean value = bool(args, "value").orElse(true);
				var refs = references(args.get("ids"));
				refs.forEach(
					ref -> ctx.store()
						.markSelected(ref, value)
				);
				var out = mapper.createObjectNode();
				out.put("marked", refs.size());
				out.put("selected", value);
				out.put(
					"totalSelected", ctx.store()
						.selected()
						.count()
				);
				return out.toString();
			}))
			.build();
	}

	// ---- crawl --------------------------------------------------------------

	private static SyncToolSpecification crawl(
			BiblioContext ctx, McpJsonMapper json
	) {
		var tool = Tool.builder()
			.name("crawl")
			.description(
				"Expand the citation graph in the background. start returns a job id immediately and the crawl"
						+ " carries on between calls; status reports visited, frontier, errors and calls per API;"
						+ " stop halts it between records. One crawl at a time. DOWN follows reference lists and is"
						+ " cheap; UP follows what cites a work and is open-ended, which is what depth and"
						+ " maxRecords are for."
			)
			.inputSchema(
				json,
				"{\"type\":\"object\",\"properties\":{"
						+ "\"action\":{\"type\":\"string\",\"enum\":[\"start\",\"status\",\"stop\"]},"
						+ "\"seeds\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
						+ "\"id\":{\"type\":\"string\"},\"idType\":{\"type\":\"string\"}},\"required\":[\"id\"]}},"
						+ "\"fromSelection\":{\"type\":\"boolean\",\"description\":\"crawl what is selected instead of an id list\"},"
						+ "\"direction\":{\"type\":\"string\",\"enum\":[\"UP\",\"DOWN\",\"BOTH\"],\"default\":\"DOWN\"},"
						+ "\"depth\":{\"type\":\"integer\",\"default\":1},"
						+ "\"maxRecords\":{\"type\":\"integer\",\"default\":1000},"
						+ "\"job\":{\"type\":\"string\"}"
						+ "},\"required\":[\"action\"]}"
			)
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(handler(ctx, (request, mapper) -> {
				var args = request.arguments();
				var action = string(args, "action").orElse("status");
				switch (action) {
					case "start": {
						Set<ImmutableRecordReference> seeds;
						if (bool(args, "fromSelection").orElse(false)) {
							seeds = ctx.store()
								.selected()
								.map(r -> r.asId())
								.collect(Collectors.toCollection(LinkedHashSet::new));
							if (seeds.isEmpty()) throw new IllegalStateException(
									"nothing is selected to crawl from; select records first,"
											+ " or pass seeds"
							);
						} else {
							seeds = references(args.get("seeds"));
							if (seeds.isEmpty()) throw new IllegalArgumentException(
									"a crawl needs seeds, or fromSelection"
							);
						}
						var job = ctx.crawl()
							.start(
								seeds,
								Direction.valueOf(
									string(args, "direction").orElse("DOWN")
										.toUpperCase()
								),
								integer(args, "depth").orElse(1),
								integer(args, "maxRecords").orElse(1000)
							);
						return jobJson(mapper, job, ctx)
							.toString();
					}
					case "stop": {
						var id = string(args, "job").orElseThrow(
							() -> new IllegalArgumentException("stop needs a job id")
						);
						boolean stopped = ctx.crawl()
							.stop(id);
						var out = mapper.createObjectNode();
						out.put("job", id);
						out.put("stopping", stopped);
						if (!stopped) out.put(
							"note", "that job is not the one running; it may have finished"
						);
						return out.toString();
					}
					default: {
						var id = string(args, "job");
						var job = id.isPresent() ? ctx.crawl()
							.status(id.get())
							: ctx.store()
								.jobs()
								.findFirst();
						if (job.isEmpty()) {
							var out = mapper.createObjectNode();
							out.put("note", "no crawl has been run in this repository");
							return out.toString();
						}
						return jobJson(mapper, job.get(), ctx)
							.toString();
					}
				}
			}))
			.build();
	}

	/** What a crawl says about itself: enough to decide whether to wait, stop it, or start reading. */
	private static ObjectNode jobJson(
			com.fasterxml.jackson.databind.ObjectMapper mapper, CrawlJob job,
			BiblioContext ctx
	) {
		var out = mapper.createObjectNode();
		out.put("job", job.getId());
		out.put(
			"status", job.getStatus()
				.name()
		);
		out.put(
			"direction", job.getDirection()
				.name()
		);
		out.put("depth", job.getDepth());
		out.put("seeds", job.getSeeds()
			.size());
		out.put(
			"visited", job.getVisited()
				.size()
		);
		out.put(
			"frontier", job.getFrontier()
				.size()
		);
		out.put("errors", job.getErrors());
		job.getStoppedBy()
			.ifPresent(v -> out.put("stoppedBy", v));
		job.getStartedAt()
			.ifPresent(v -> out.put("startedAt", v.toString()));
		job.getFinishedAt()
			.ifPresent(v -> out.put("finishedAt", v.toString()));
		var calls = out.putObject("callsPerApi");
		job.getCallsPerApi()
			.forEach(calls::put);
		out.put(
			"recordsInRepository", ctx.store()
				.recordCount()
		);
		out.put(
			"citationsInRepository", ctx.store()
				.citationCount()
		);
		return out;
	}

	// ---- rank ---------------------------------------------------------------

	private static SyncToolSpecification rank(
			BiblioContext ctx, McpJsonMapper json
	) {
		var tool = Tool.builder()
			.name("rank")
			.description(
				"Rank what the repository holds. page_rank scores centrality in the citation graph the crawl"
						+ " collected; cited_by counts how many records in the repository cite each work; rrf"
						+ " returns the search scores as they were. All three answer \"in this corpus\", not"
						+ " \"in the literature\". Scores are written back, so list_records agrees with the ranking."
			)
			.inputSchema(
				json,
				"{\"type\":\"object\",\"properties\":{"
						+ "\"method\":{\"type\":\"string\",\"enum\":[\"page_rank\",\"cited_by\",\"rrf\"],\"default\":\"page_rank\"},"
						+ "\"top\":{\"type\":\"integer\",\"default\":25},"
						+ "\"onlySelected\":{\"type\":\"boolean\",\"default\":false},"
						+ "\"style\":{\"type\":\"string\"}"
						+ "}}"
			)
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(handler(ctx, (request, mapper) -> {
				var args = request.arguments();
				var method = string(args, "method").orElse("page_rank");
				int top = integer(args, "top").orElse(25);
				boolean onlySelected = bool(args, "onlySelected").orElse(false);
				var style = string(args, "style").orElse(null);
				var selected = onlySelected ? ctx.store()
					.selected()
					.map(Record::asId)
					.collect(Collectors.toSet()) : null;

				var out = mapper.createArrayNode();
				if ("cited_by".equals(method)) {
					for (var entry : ctx.store()
						.topByCitedBy(onlySelected ? top * 10 : top)) {
						var record = entry.getKey();
						if (selected != null && !selected.contains(record.asId()))
							continue;
						ctx.store()
							.setScore(record, entry.getValue());
						var node = RecordJson.of(
							mapper, record, style, (double) entry.getValue()
						);
						node.put("citedBy", entry.getValue());
						out.add(node);
						if (out.size() >= top) break;
					}
				} else if ("rrf".equals(method)) {
					ctx.store()
						.topByScore(top)
						.filter(
							r -> selected == null || selected.contains(r.asId())
						)
						.forEach(r -> out.add(RecordJson.of(mapper, r, style, null)));
				} else {
					var scores = Ranking.pageRank(ctx.store());
					scores.entrySet()
						.stream()
						.filter(
							e -> selected == null || selected.contains(e.getKey())
						)
						.sorted(
							(a, b) -> Double.compare(b.getValue(), a.getValue())
						)
						.limit(top)
						.forEach(e -> {
							ctx.store()
								.setScore(e.getKey(), e.getValue());
							var record = ctx.store()
								.getRecord(e.getKey());
							if (record.isEmpty()) return;
							out.add(
								RecordJson
									.of(mapper, record.get(), style, e.getValue())
							);
						});
				}
				return out.toString();
			}))
			.build();
	}

	// ---- zotero_sync --------------------------------------------------------

	private static SyncToolSpecification zoteroSync(
			BiblioContext ctx, McpJsonMapper json
	) {
		var tool = Tool.builder()
			.name("zotero_sync")
			.description(
				"Mirror the selection into Zotero. collections lists what the library has; report says which"
						+ " selected records it already holds and which it does not; publish creates a"
						+ " sub-collection of the configured write target and adds what is missing. One way out:"
						+ " the repository is the record, Zotero is the human's view of it. Nothing existing is"
						+ " modified, and a work the library already holds is reported rather than duplicated."
			)
			.inputSchema(
				json,
				"{\"type\":\"object\",\"properties\":{"
						+ "\"action\":{\"type\":\"string\",\"enum\":[\"collections\",\"report\",\"publish\"]},"
						+ "\"collection\":{\"type\":\"string\",\"description\":\"name for the sub-collection to publish into\"},"
						+ "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
						+ "\"includePdf\":{\"type\":\"boolean\",\"description\":\"download each open-access pdf and attach it\"},"
						+ "\"limit\":{\"type\":\"integer\",\"description\":\"most items to create in one call (default 25)\"}"
						+ "},\"required\":[\"action\"]}"
			)
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(handler(ctx, (request, mapper) -> {
				var args = request.arguments();
				var action = string(args, "action").orElse("report");
				var zotero = ctx.requireZotero();

				if ("collections".equals(action)) {
					var out = mapper.createArrayNode();
					zotero.collections()
						.forEach(c -> {
							var row = out.addObject();
							row.put("key", c.getKey());
							row.put("name", c.getName());
						});
					return out.toString();
				}

				var selected = ctx.store()
					.selected()
					.collect(Collectors.toList());
				if (selected.isEmpty()) throw new IllegalStateException(
						"nothing is selected; select the records to mirror before syncing"
				);
				var index = zotero.buildIndex();

				if ("report".equals(action)) {
					var out = mapper.createObjectNode();
					var held = out.putArray("alreadyHeld");
					var missing = out.putArray("notHeld");
					for (Record record : selected) {
						var node = index.findKey(record)
							.isPresent() ? held : missing;
						var row = node.addObject();
						record.getIdentifier()
							.ifPresent(v -> row.put("id", v));
						record.getTitle()
							.ifPresent(v -> row.put("title", v));
					}
					out.put("selected", selected.size());
					return out.toString();
				}

				// publish
				zotero.checkWritesAllowed();
				var name = string(args, "collection").orElseThrow(
					() -> new IllegalArgumentException(
							"publish needs a collection name to create"
					)
				);
				var tags = strings(args, "tags");
				int limit = integer(args, "limit").orElse(25);

				// choose before writing: nothing is created in a real library until we
				// know there is something to write and that it can be written
				List<Record> toWrite = new ArrayList<>();
				var skipped = mapper.createArrayNode();
				int alreadyHeld = 0;
				int overLimit = 0;
				for (Record record : selected) {
					if (index.findKey(record)
						.isPresent()) { alreadyHeld++; continue; }
					if (toWrite.size() >= limit) { overLimit++; continue; }
					try {
						ZoteroItemWriter.toItem(record, null, tags);
						toWrite.add(record);
					} catch (IllegalArgumentException e) {
						var row = skipped.addObject();
						record.getTitle()
							.ifPresent(v -> row.put("title", v));
						row.put("reason", e.getMessage());
					}
				}

				String target = null;
				int created = 0;
				int attached = 0;
				var pdfProblems = mapper.createArrayNode();
				if (!toWrite.isEmpty()) {
					target = zotero.createSubCollection(name);
					List<com.fasterxml.jackson.databind.JsonNode> items = new ArrayList<>();
					for (Record record : toWrite)
						items.add(ZoteroItemWriter.toItem(record, target, tags));
					var result = zotero.createItems(items, target);
					created = result.created.size();

					if (bool(args, "includePdf").orElse(false)) {
						// the index is what ties a created key back to the record it was
						// made from, and therefore to the pdf that belongs to it
						for (var entry : result.createdByIndex.entrySet()) {
							var record = toWrite.get(entry.getKey());
							var problem = attachPdf(
								ctx, zotero, record, entry.getValue(), target
							);
							if (problem == null) attached++;
							else {
								var row = pdfProblems.addObject();
								record.getIdentifier()
									.ifPresent(v -> row.put("id", v));
								row.put("reason", problem);
							}
						}
					}
				}

				var out = mapper.createObjectNode();
				out.put("pdfsAttached", attached);
				if (pdfProblems.size() > 0) out.set("pdfsNotAttached", pdfProblems);
				out.put(
					"collection", target == null ? "(nothing to publish)" : target
				);
				out.put("selected", selected.size());
				out.put("createdCount", created);
				out.put("alreadyInLibrary", alreadyHeld);
				out.put("leftForANextCall", overLimit);
				out.set("skipped", skipped);
				return out.toString();
			}))
			.build();
	}

	/**
	 * Download a record's open-access PDF and attach it to the item just created.
	 *
	 * Returns null when it worked, and why not when it did not. A missing PDF is the ordinary case rather than a
	 * failure: `best_oa_location` points at the publisher for most records, and publishers refuse programmatic
	 * clients often enough that a crawl attaches PDFs for a minority of what it collects (prototype-003).
	 */
	private static String attachPdf(
			BiblioContext ctx, uk.co.terminological.bibliography.api.zotero.ZoteroClient zotero,
			Record record, String itemKey, String collectionKey
	) {
		var uri = record.getPdfUri();
		if (uri.isEmpty()) return "no open-access pdf is known for it";
		try {
			var destination = uk.co.terminological.bibliography.PdfSink
				.pathFor(ctx.pdfDirectory(), record);
			var file = ctx.facade()
				.pdfFetcher()
				.fetchTo(
					uri.get()
						.toString(),
					destination
				);
			if (file.isEmpty()) return "the publisher returned nothing for "
					+ uri.get();
			zotero.attachPdf(itemKey, collectionKey, file.get());
			return null;
		} catch (java.io.IOException e) {
			// PdfSink refuses an html paywall page, which is the usual way this fails
			return e.getMessage();
		} catch (RuntimeException e) {
			return e.getMessage();
		}
	}

	// ---- plumbing -----------------------------------------------------------

	/** What a handler does: turn arguments into a JSON string, or throw. */
	interface Body {
		String apply(
				CallToolRequest request,
				com.fasterxml.jackson.databind.ObjectMapper mapper
		) throws Exception;
	}

	/**
	 * Wrap a handler so a failure reaches the caller as a tool error rather than a stack trace.
	 *
	 * The message matters more than the trace: "Zotero support is switched off" is actionable, and the SDK has
	 * already rejected anything the schema could catch before this runs.
	 */
	private static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler(
			BiblioContext ctx, Body body
	) {
		return (exchange, request) -> {
			try {
				return CallToolResult.builder()
					.addTextContent(body.apply(request, ctx.mapper()))
					.isError(false)
					.build();
			} catch (IllegalStateException | IllegalArgumentException e) {
				log.warn("{} refused: {}", request.name(), e.getMessage());
				return CallToolResult.builder()
					.addTextContent(e.getMessage())
					.isError(true)
					.build();
			} catch (Exception e) {
				log.error("{} failed", request.name(), e);
				return CallToolResult.builder()
					.addTextContent(
						e.getClass()
							.getSimpleName() + ": " + e.getMessage()
					)
					.isError(true)
					.build();
			}
		};
	}

	static Optional<String> string(Map<String, Object> args, String key) {
		var value = args.get(key);
		if (value == null) return Optional.empty();
		var text = String.valueOf(value)
			.trim();
		return text.isEmpty() ? Optional.empty() : Optional.of(text);
	}

	static Optional<Integer> integer(Map<String, Object> args, String key) {
		var value = args.get(key);
		if (value instanceof Number) return Optional.of(((Number) value).intValue());
		return string(args, key).map(Integer::parseInt);
	}

	static Optional<Boolean> bool(Map<String, Object> args, String key) {
		var value = args.get(key);
		if (value instanceof Boolean) return Optional.of((Boolean) value);
		return string(args, key).map(Boolean::parseBoolean);
	}

	@SuppressWarnings("unchecked")
	static List<String> strings(Map<String, Object> args, String key) {
		var value = args.get(key);
		if (!(value instanceof List)) return List.of();
		return ((List<Object>) value).stream()
			.map(String::valueOf)
			.collect(Collectors.toList());
	}

	/**
	 * Identifiers from a tool argument.
	 *
	 * The type is optional and defaults to DOI, because that is what a caller almost always has and asking for it
	 * twice is ceremony. An unknown type is refused by name rather than guessed at.
	 */
	@SuppressWarnings("unchecked")
	static Set<ImmutableRecordReference> references(Object ids) {
		Set<ImmutableRecordReference> out = new LinkedHashSet<>();
		if (!(ids instanceof List)) return out;
		for (Object entry : (List<Object>) ids) {
			String id;
			String type = "DOI";
			if (entry instanceof Map) {
				var map = (Map<String, Object>) entry;
				id = map.get("id") == null ? null
					: String.valueOf(map.get("id"));
				if (map.get("idType") != null)
					type = String.valueOf(map.get("idType"));
			} else {
				id = String.valueOf(entry);
			}
			if (id == null || id.isBlank()) continue;
			try {
				out.add(
					Builder.recordReference(
						IdType.valueOf(type.toUpperCase()), id.trim()
					)
				);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException(
						"unknown idType '" + type + "' for " + id
								+ "; expected one of DOI, PMID, PMCID, OPENALEX, ISBN"
				);
			}
		}
		return out;
	}

}
