package uk.co.terminological.zotero;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The Zotero **local** API (`http://localhost:23119/api`): the user's own library,
 * read through the same interface as the Web API but with no rate limits, no read
 * authentication and no group libraries.
 *
 * Deliberately not a {@code CachingApiClient}: library contents are mutable user
 * data with their own versioning, and a URL-keyed cache would serve stale answers
 * (see plan-003 and debt-006). Reads are cheap locally; the only client-side state
 * is the small in-memory match index.
 *
 * Writes are additions only, are refused unless configured, and are confined to
 * the configured collection — a Zotero item is never overwritten with API data.
 *
 * [IMPLEMENTS](/design/features/feat-005-zotero-curated-repository-bridge.md)
 */
public class ZoteroClient {

	/** a Zotero-specific failure, with a message that says what to do about it */
	public static class ZoteroException extends RuntimeException {
		public ZoteroException(String message) { super(message); }
		public ZoteroException(String message, Throwable cause) { super(message, cause); }
	}

	private static final Logger log = LoggerFactory.getLogger(ZoteroClient.class);

	private static final String API_VERSION = "3";
	private static final int MAX_PER_WRITE = 50;

	private final ZoteroConfig config;
	private final ObjectMapper mapper = new ObjectMapper();
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private String serverId;
	private String apiVersion;
	private String schemaVersion;
	private long lastLibraryVersion = -1L;
	private String writeKey;

	public ZoteroClient(ZoteroConfig config) {
		this.config = config;
	}

	public ZoteroConfig getConfig() { return config; }

	// ---- status --------------------------------------------------------------

	public boolean isReachable() {
		if (!config.isEnabled()) return false;
		try {
			request("GET", "/", null, null, false, false);
			return true;
		} catch (ZoteroException e) {
			return false;
		}
	}

	/** throws unless the local API is answering, so callers fail fast with guidance */
	public void checkAvailable() {
		if (!config.isEnabled()) {
			throw new ZoteroException("Zotero support is switched off (zotero-mode=off in the properties file)");
		}
		request("GET", "/", null, null, false, false);
	}

	public String getServerId() { return serverId; }

	public String getApiVersion() { return apiVersion; }

	public String getSchemaVersion() { return schemaVersion; }

	public long getLastLibraryVersion() { return lastLibraryVersion; }

	// ---- reads ---------------------------------------------------------------

	public List<ZoteroCollection> collections() {
		List<ZoteroCollection> out = new ArrayList<>();
		for (JsonNode node : getArray(userPath("/collections"))) {
			JsonNode data = node.path("data");
			out.add(new ZoteroCollection(
					node.path("key").asText(null),
					data.path("name").asText(null),
					data.path("parentCollection").isTextual() ? data.path("parentCollection").asText() : null));
		}
		return out;
	}

	/** every bibliographic item in the library, children excluded (one read) */
	public List<ZoteroItem> itemsTop() {
		return items(userPath("/items/top"));
	}

	public List<ZoteroItem> itemsInCollection(String collectionKey) {
		return items(userPath("/collections/" + collectionKey + "/items/top"));
	}

	public List<ZoteroItem> search(String query) {
		return items(userPath("/items/top") + "?q=" + encode(query) + "&qmode=everything");
	}

	public Optional<ZoteroItem> item(String itemKey) {
		JsonNode response = get(userPath("/items/" + itemKey));
		if (response == null || response.isMissingNode()) return Optional.empty();
		JsonNode data = response.has("data") ? response.get("data") : response;
		return Optional.of(new ZoteroItem(data, serverId));
	}

	public ZoteroLibraryIndex buildIndex() {
		return ZoteroLibraryIndex.of(itemsTop(), serverId, lastLibraryVersion);
	}

	/** collection keys for an item, used to check where a write would land */
	public List<String> collectionsOf(String itemKey) {
		return item(itemKey)
				.map(i -> {
					List<String> out = new ArrayList<>();
					JsonNode collections = i.getData().path("collections");
					if (collections.isArray()) collections.forEach(c -> out.add(c.asText()));
					return out;
				})
				.orElseGet(List::of);
	}

	// ---- writes --------------------------------------------------------------

	/** whether a write is permitted at all, with a message explaining the refusal */
	public void checkWritesAllowed() {
		if (!config.isAllowWrites()) {
			throw new ZoteroException("Zotero writes are disabled: set zotero-allow-writes=true in the properties file");
		}
		if (config.getWriteCollection().isEmpty()) {
			throw new ZoteroException("Zotero writes have no target: set zotero-write-collection=<collection key>");
		}
	}

	/** writes may only touch the configured collection or something inside it */
	public void checkWriteTarget(String collectionKey) {
		checkWritesAllowed();
		if (collectionKey.equals(config.getWriteCollection())) return;
		if (isDescendantOf(collectionKey, config.getWriteCollection())) return;
		throw new ZoteroException("refusing to write to collection " + collectionKey
				+ ": writes are confined to the configured collection (" + config.getWriteCollection() + ")");
	}

	private boolean isDescendantOf(String collectionKey, String ancestorKey) {
		List<ZoteroCollection> all = collections();
		String current = collectionKey;
		for (int hops = 0; hops < 20 && current != null && !current.isEmpty(); hops++) {
			String parent = null;
			for (ZoteroCollection collection : all) {
				if (current.equals(collection.getKey())) {
					parent = collection.getParentCollection();
					break;
				}
			}
			if (parent == null) return false;
			if (parent.equals(ancestorKey)) return true;
			current = parent;
		}
		return false;
	}

	/** create a sub-collection of the configured write target, and return its key */
	public String createSubCollection(String name) {
		checkWritesAllowed();
		ObjectNode collection = mapper.createObjectNode();
		collection.put("name", name);
		collection.put("parentCollection", config.getWriteCollection());
		ArrayNode body = mapper.createArrayNode().add(collection);
		JsonNode response = write("POST", userPath("/collections"), body.toString());
		JsonNode successful = response.path("successful");
		if (successful.isObject() && successful.size() > 0) {
			JsonNode first = successful.elements().next();
			if (first.isTextual()) return first.asText();
			if (first.has("key")) return first.get("key").asText();
		}
		throw new ZoteroException("Zotero did not report a created collection: " + truncate(response.toString()));
	}

	/** create items in the given collection, in batches Zotero accepts (≤50) */
	public List<String> createItems(List<JsonNode> items, String collectionKey) {
		checkWriteTarget(collectionKey);
		List<String> created = new ArrayList<>();
		for (int start = 0; start < items.size(); start += MAX_PER_WRITE) {
			List<JsonNode> batch = items.subList(start, Math.min(items.size(), start + MAX_PER_WRITE));
			ArrayNode body = mapper.createArrayNode();
			batch.forEach(body::add);
			JsonNode response = write("POST", userPath("/items"), body.toString());
			response.path("successful")
					.fields()
					.forEachRemaining(entry -> created.add(entry.getValue().asText()));
			// Zotero judges each item separately: log a refusal and let the rest of the
			// batch land, rather than discarding a partial success
			response.path("failed")
					.fields()
					.forEachRemaining(entry -> log.warn("Zotero refused an item: {}", truncate(entry.getValue().toString())));
		}
		return created;
	}

	/**
	 * Add tags to an item, keeping the ones it already has. Arrays in a PATCH are a
	 * complete replacement, so this is a read-merge-write with the item's version as
	 * a precondition: a concurrent edit returns 412 and is retried once from a fresh
	 * read rather than clobbering it.
	 */
	public List<String> addTags(String itemKey, List<String> tags) {
		checkWriteTarget(itemKeyCollection(itemKey));
		for (int attempt = 0; attempt < 2; attempt++) {
			ZoteroItem item = item(itemKey).orElseThrow(() -> new ZoteroException("no such Zotero item: " + itemKey));
			long version = item.getData().path("version").asLong(0);
			java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>(ZoteroItemWriter.tagNames(item.getData()));
			for (String tag : tags) {
				if (tag != null && !tag.trim().isEmpty()) union.add(tag.trim());
			}
			ObjectNode patch = mapper.createObjectNode();
			ArrayNode tagArray = patch.putArray("tags");
			union.forEach(t -> tagArray.addObject().put("tag", t));
			HttpResponse<String> response = send("PATCH", userPath("/items/" + itemKey), null,
					patch.toString(), true, true, version);
			if (response.statusCode() == 412 && attempt == 0) continue; // someone else edited it; re-read and re-merge
			requireSuccess(response, "tagging item " + itemKey);
			return new ArrayList<>(union);
		}
		throw new ZoteroException("could not tag " + itemKey + ": the item keeps changing underneath us");
	}

	private String itemKeyCollection(String itemKey) {
		List<String> memberships = collectionsOf(itemKey);
		if (memberships.isEmpty()) {
			throw new ZoteroException("refusing to write to item " + itemKey
					+ ": it is not in the configured collection (" + config.getWriteCollection() + ")");
		}
		return memberships.get(0);
	}

	// ---- http ----------------------------------------------------------------

	private String userPath(String suffix) {
		return "/users/" + config.getUserId() + suffix;
	}

	private List<ZoteroItem> items(String path) {
		List<ZoteroItem> out = new ArrayList<>();
		for (JsonNode node : getArray(path)) {
			out.add(new ZoteroItem(node.path("data"), serverId));
		}
		return out;
	}

	private JsonNode getArray(String path) {
		JsonNode response = get(path);
		if (response == null || response.isMissingNode() || response.isNull()) return mapper.createArrayNode();
		if (!response.isArray()) {
			throw new ZoteroException("expected a list from " + path + " but got: " + truncate(response.toString()));
		}
		return response;
	}

	private JsonNode get(String path) {
		HttpResponse<String> response = send("GET", path, null, null, false, false, null);
		requireSuccess(response, "reading " + path);
		try {
			return mapper.readTree(response.body());
		} catch (Exception e) {
			throw new ZoteroException("could not parse the Zotero response from " + path, e);
		}
	}

	/** a write with preconditions; JZotero requires Zotero-Server-ID and, from 10+, a local API key */
	private JsonNode write(String method, String path, String body) {
		ensureServerId();
		HttpResponse<String> response = send(method, path, null, body, true, true, null);
		requireSuccess(response, method + " " + path);
		try {
			return mapper.readTree(response.body());
		} catch (Exception e) {
			throw new ZoteroException("could not parse the Zotero response from " + path, e);
		}
	}

	private void ensureServerId() {
		if (serverId == null) request("GET", "/", null, null, false, false);
		if (serverId == null) {
			throw new ZoteroException("Zotero did not report a Zotero-Server-ID header; writes need one");
		}
	}

	private HttpResponse<String> send(String method, String path, String query, String body,
			boolean write, boolean mayAuthorise, Long versionPrecondition) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path, query))
				.timeout(Duration.ofSeconds(30))
				.header("Zotero-API-Version", API_VERSION)
				.header("Accept", "application/json");
		if (write) {
			if (serverId != null) builder.header("Zotero-Server-ID", serverId);
			if (writeKey != null) builder.header("Zotero-API-Key", writeKey);
			if (versionPrecondition != null) builder.header("If-Unmodified-Since-Version", versionPrecondition.toString());
		}
		if ("POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method)) {
			builder.header("Content-Type", "application/json");
			builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}

		HttpResponse<String> response;
		try {
			response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		} catch (java.net.ConnectException e) {
			throw new ZoteroException("the Zotero local API is not reachable at " + config.getBaseUrl()
					+ " — is Zotero running? (the local API is off by default; see the Zotero preferences)", e);
		} catch (Exception e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new ZoteroException("could not talk to the Zotero local API at " + config.getBaseUrl(), e);
		}
		captureHeaders(response);

		if (write && response.statusCode() == 401 && mayAuthorise) {
			authoriseWrite();
			return send(method, path, query, body, true, false, versionPrecondition);
		}
		if (write && response.statusCode() == 428 && mayAuthorise) {
			// the server wants its own id; take it from this very response and retry
			ensureServerId();
			return send(method, path, query, body, true, false, versionPrecondition);
		}
		return response;
	}

	/**
	 * Ask the user for permission to write. Zotero shows a dialog naming this
	 * application; the key it returns is single-use unless the user chose
	 * "Always Allow".
	 */
	private void authoriseWrite() {
		ensureServerId();
		ObjectNode body = mapper.createObjectNode();
		body.put("appName", ZoteroConfig.APP_NAME);
		HttpRequest request = HttpRequest.newBuilder(uri("/local/authorize", null))
				.timeout(Duration.ofSeconds(120))
				.header("Zotero-API-Version", API_VERSION)
				.header("Zotero-Server-ID", serverId)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (Exception e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new ZoteroException("could not ask Zotero for write permission", e);
		}
		if (response.statusCode() == 403) {
			throw new ZoteroException("the Zotero write request was declined — approve the dialog in Zotero to continue");
		}
		if (response.statusCode() == 429) {
			throw new ZoteroException("Zotero is limiting permission dialogs; wait a minute and try again");
		}
		if (response.statusCode() != 200) {
			throw new ZoteroException("Zotero refused write permission (HTTP " + response.statusCode() + "): "
					+ truncate(response.body()));
		}
		try {
			writeKey = mapper.readTree(response.body()).path("key").asText(null);
		} catch (Exception e) {
			throw new ZoteroException("could not read the key Zotero returned for writes", e);
		}
		if (writeKey == null) throw new ZoteroException("Zotero returned no write key");
	}

	private void captureHeaders(HttpResponse<String> response) {
		response.headers().firstValue("Zotero-API-Version").ifPresent(v -> apiVersion = v);
		response.headers().firstValue("Zotero-Schema-Version").ifPresent(v -> schemaVersion = v);
		response.headers().firstValue("Zotero-Server-ID").ifPresent(v -> serverId = v);
		response.headers().firstValue("Last-Modified-Version")
				.ifPresent(v -> {
					try {
						lastLibraryVersion = Long.parseLong(v.trim());
					} catch (NumberFormatException ignored) { }
				});
	}

	private void requireSuccess(HttpResponse<String> response, String what) {
		if (response.statusCode() >= 200 && response.statusCode() < 300) return;
		if (response.statusCode() == 403) {
			throw new ZoteroException("Zotero refused " + what + " (403). If this is a read, the local API "
					+ "preference is off: Zotero Settings → Advanced → \"Allow other applications on this "
					+ "computer to communicate with Zotero\"");
		}
		throw new ZoteroException("Zotero returned HTTP " + response.statusCode() + " for " + what + ": "
				+ truncate(response.body()));
	}

	private URI uri(String path, String query) {
		String url = config.getBaseUrl() + (path.startsWith("/") ? path : "/" + path);
		if (query != null && !query.isEmpty()) url = url + "?" + query;
		return URI.create(url);
	}

	/** one request, for callers that only want the side effect of capturing headers */
	private JsonNode request(String method, String path, String query, String body, boolean write, boolean mayAuthorise) {
		HttpResponse<String> response = send(method, path, query, body, write, mayAuthorise, null);
		requireSuccess(response, method + " " + path);
		try {
			return mapper.readTree(response.body());
		} catch (Exception e) {
			return mapper.createObjectNode(); // e.g. the base endpoint answers with text
		}
	}

	private static String encode(String value) {
		try {
			return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
		} catch (UnsupportedEncodingException e) {
			throw new ZoteroException("could not encode a query parameter", e);
		}
	}

	private static String truncate(String text) {
		if (text == null) return "";
		return text.length() > 200 ? text.substring(0, 200) + "…" : text;
	}
}
