package uk.co.terminological.bibliography.api.zotero;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The Zotero **local** API (`http://localhost:23119/api`): the user's own library, read through the same interface as
 * the Web API but with no rate limits, no read authentication and no group libraries.
 *
 * Deliberately not a {@code CachingApiClient}: library contents are mutable user data with their own versioning, and a
 * URL-keyed cache would serve stale answers (see plan-003 and debt-006). Reads are cheap locally; the only client-side
 * state is the small in-memory match index.
 *
 * Writes are additions only, are refused unless configured, and are confined to the configured collection — a Zotero
 * item is never overwritten with API data.
 *
 * [IMPLEMENTS](/design/features/feat-005-zotero-curated-repository-bridge.md)
 */
// [IMPLEMENTS](/design/features/feat-008-clean-up-collection-metadata.md)
public class ZoteroClient {

	/** a Zotero-specific failure, with a message that says what to do about it */
	public static class ZoteroException extends RuntimeException {
		public ZoteroException(String message) {
			super(message);
		}
		public ZoteroException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private static final Logger log = LoggerFactory
		.getLogger(ZoteroClient.class);

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
	/** whether Zotero will let the key above be reused, or expects a fresh prompt */
	private Boolean writeKeyRemembered;

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
			throw new ZoteroException(
					"Zotero support is switched off (zotero-mode=off in the properties file)"
			);
		}
		request("GET", "/", null, null, false, false);
	}

	/** how a write would be authorised right now, so a caller can advise the user */
	public String getWriteAuthorisation() {
		if (writeKey == null) return "not asked yet";
		return Boolean.TRUE.equals(writeKeyRemembered) ? "remembered"
			: "single-use";
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
			out.add(
				new ZoteroCollection(
						node.path("key")
							.asText(null),
						data.path("name")
							.asText(null),
						data.path("parentCollection")
							.isTextual()
								? data.path("parentCollection")
									.asText()
								: null
				)
			);
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
		return items(
			userPath("/items/top") + "?q=" + encode(query) + "&qmode=everything"
		);
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
		return item(itemKey).map(i -> {
			List<String> out = new ArrayList<>();
			JsonNode collections = i.getData()
				.path("collections");
			if (collections.isArray())
				collections.forEach(c -> out.add(c.asText()));
			return out;
		})
			.orElseGet(List::of);
	}

	// ---- writes --------------------------------------------------------------

	/** whether a write is permitted at all, with a message explaining the refusal */
	public void checkWritesAllowed() {
		if (!config.isAllowWrites()) {
			throw new ZoteroException(
					"Zotero writes are disabled: set zotero-allow-writes=true in the properties file"
			);
		}
		if (config.getWriteCollection()
			.isEmpty()) {
			throw new ZoteroException(
					"Zotero writes have no target: set zotero-write-collection=<collection key>"
			);
		}
	}

	/** writes may only touch the configured collection or something inside it */
	public void checkWriteTarget(String collectionKey) {
		checkWritesAllowed();
		if (collectionKey.equals(config.getWriteCollection())) return;
		if (isDescendantOf(collectionKey, config.getWriteCollection())) return;
		throw new ZoteroException(
				"refusing to write to collection " + collectionKey
						+ ": writes are confined to the configured collection ("
						+ config.getWriteCollection() + ")"
		);
	}

	private boolean isDescendantOf(String collectionKey, String ancestorKey) {
		List<ZoteroCollection> all = collections();
		String current = collectionKey;
		for (int hops = 0; hops < 20 && current != null
				&& !current.isEmpty(); hops++) {
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
		ArrayNode body = mapper.createArrayNode()
			.add(collection);
		JsonNode response = write(
			"POST",
			userPath("/collections"),
			body.toString()
		);
		JsonNode successful = response.path("successful");
		if (successful.isObject() && successful.size() > 0) {
			JsonNode first = successful.elements()
				.next();
			if (first.isTextual()) return first.asText();
			if (first.has("key")) return first.get("key")
				.asText();
		}
		throw new ZoteroException(
				"Zotero did not report a created collection: "
						+ truncate(response.toString())
		);
	}

	/** create items in the given collection, in batches Zotero accepts (≤50) */
	/** what a batch write actually did, so a partial success is never lost */
	public static class WriteResult {
		public final List<String> created = new ArrayList<>();
		public final List<String> failures = new ArrayList<>();
		/** for an update, the values that were there before, per item key */
		public final Map<String, Map<String, String>> previous = new LinkedHashMap<>();

		public boolean hasFailures() {
			return !failures.isEmpty();
		}
	}

	public WriteResult createItems(List<JsonNode> items, String collectionKey) {
		checkWriteTarget(collectionKey);
		WriteResult result = new WriteResult();
		for (int start = 0; start < items.size(); start += MAX_PER_WRITE) {
			List<JsonNode> batch = items
				.subList(start, Math.min(items.size(), start + MAX_PER_WRITE));
			ArrayNode body = mapper.createArrayNode();
			batch.forEach(body::add);
			collect(write("POST", userPath("/items"), body.toString()), result);
		}
		return result;
	}

	/**
	 * Apply explicit field changes to existing items, in one request for up to 50.
	 *
	 * This is the *overwrite* path, and it exists because clean-up is a legitimate reason to overwrite: the agent has the
	 * user's agreement (or has judged the change uncontroversial), and the tool's job is to do exactly what it is told.
	 * Only the named fields change — a multi-object write follows PATCH semantics, so tags, collections, relations and
	 * attachments are untouched — and the values that were there before are reported back so the change is reviewable.
	 */
	public WriteResult updateItems(
			Map<String, Map<String, String>> fieldsByItemKey
	) {
		for (String itemKey : fieldsByItemKey.keySet()) {
			checkWriteTarget(itemKeyCollection(itemKey));
		}
		WriteResult result = new WriteResult();
		List<String> keys = new ArrayList<>(fieldsByItemKey.keySet());
		for (int start = 0; start < keys.size(); start += MAX_PER_WRITE) {
			List<String> batch = keys
				.subList(start, Math.min(keys.size(), start + MAX_PER_WRITE));
			ArrayNode body = mapper.createArrayNode();
			for (String itemKey : batch) {
				Map<String, String> fields = fieldsByItemKey.get(itemKey);
				ZoteroItem current = item(itemKey).orElseThrow(
					() -> new ZoteroException("no such Zotero item: " + itemKey)
				);
				Map<String, String> previous = new LinkedHashMap<>();
				fields.keySet()
					.forEach(
						field -> previous
							.put(field, previousValue(current.getData(), field))
					);
				result.previous.put(itemKey, previous);
				body.add(
					ZoteroItemWriter.updatePatch(
						itemKey,
						current.getData()
							.path("version")
							.asLong(0),
						fields
					)
				);
			}
			collect(write("POST", userPath("/items"), body.toString()), result);
		}
		return result;
	}

	private static String previousValue(JsonNode itemData, String field) {
		JsonNode node = itemData.get(field);
		return node == null || node.isNull() ? null : node.asText();
	}

	/**
	 * Tag many items in as few requests as Zotero allows (up to 50 objects each). A write authorisation is usually
	 * single-use, so one request per item would ask the user to approve every item and would hit Zotero's limit of five
	 * permission prompts a minute; batching makes a whole tagging call one prompt.
	 *
	 * Tags are a complete replacement in a write, so each item's tags are read, unioned with the new ones and written back
	 * with the version that was read as a precondition: a concurrent edit fails that item rather than clobbering it.
	 */
	public WriteResult addTags(List<String> itemKeys, List<String> tags) {
		for (String itemKey : itemKeys)
			checkWriteTarget(itemKeyCollection(itemKey));
		WriteResult result = new WriteResult();
		for (int start = 0; start < itemKeys.size(); start += MAX_PER_WRITE) {
			List<String> batch = itemKeys
				.subList(start, Math.min(itemKeys.size(), start + MAX_PER_WRITE));
			ArrayNode body = mapper.createArrayNode();
			for (String itemKey : batch) {
				body.add(
					ZoteroItemWriter.tagPatch(
						itemKey,
						itemData(itemKey).path("version")
							.asLong(0),
						itemData(itemKey),
						tags
					)
				);
			}
			collect(write("POST", userPath("/items"), body.toString()), result);
		}
		return result;
	}

	/** split Zotero's per-object verdict into what landed and what did not (package-private for testing) */
	static void collect(JsonNode response, WriteResult result) {
		for (String status : List.of("successful", "unchanged")) {
			response.path(status)
				.fields()
				.forEachRemaining(entry -> {
					// Zotero answers either with the key as a string or with an object
					// carrying the key and its new version: `asText()` on the object form
					// yields an empty string, which is useless to a caller that wants to
					// tag the item it just created
					JsonNode value = entry.getValue();
					String key = value.isTextual() ? value.asText()
						: value.path("key")
							.asText(null);
					result.created
						.add(key == null || key.isEmpty() ? entry.getKey() : key);
				});
		}
		response.path("failed")
			.fields()
			.forEachRemaining(
				entry -> result.failures.add(
					truncate(
						entry.getValue()
							.toString()
					)
				)
			);
		if (result.created.isEmpty() && result.failures.isEmpty()) {
			log.warn(
				"Zotero accepted a write but reported nothing: {}",
				truncate(response.toString())
			);
		}
	}

	private JsonNode itemData(String itemKey) {
		return item(itemKey)
			.orElseThrow(
				() -> new ZoteroException("no such Zotero item: " + itemKey)
			)
			.getData();
	}

	private String itemKeyCollection(String itemKey) {
		List<String> memberships = collectionsOf(itemKey);
		if (memberships.isEmpty()) {
			throw new ZoteroException(
					"refusing to write to item " + itemKey
							+ ": it is not in the configured collection ("
							+ config.getWriteCollection() + ")"
			);
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
		if (response == null || response.isMissingNode() || response.isNull())
			return mapper.createArrayNode();
		if (!response.isArray()) {
			throw new ZoteroException(
					"expected a list from " + path + " but got: "
							+ truncate(response.toString())
			);
		}
		return response;
	}

	private JsonNode get(String path) {
		HttpResponse<String> response = send(
			"GET",
			path,
			null,
			null,
			false,
			false,
			null
		);
		requireSuccess(response, "reading " + path);
		try {
			return mapper.readTree(response.body());
		} catch (Exception e) {
			throw new ZoteroException(
					"could not parse the Zotero response from " + path, e
			);
		}
	}

	/** a write with preconditions; JZotero requires Zotero-Server-ID and, from 10+, a local API key */
	private JsonNode write(String method, String path, String body) {
		ensureServerId();
		HttpResponse<String> response = send(
			method,
			path,
			null,
			body,
			true,
			true,
			null
		);
		requireSuccess(response, method + " " + path);
		try {
			return mapper.readTree(response.body());
		} catch (Exception e) {
			throw new ZoteroException(
					"could not parse the Zotero response from " + path, e
			);
		}
	}

	private void ensureServerId() {
		if (serverId == null) request("GET", "/", null, null, false, false);
		if (serverId == null) {
			throw new ZoteroException(
					"Zotero did not report a Zotero-Server-ID header; writes need one"
			);
		}
	}

	private HttpResponse<String> send(
			String method, String path, String query, String body, boolean write,
			boolean mayAuthorise, Long versionPrecondition
	) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path, query))
			.timeout(Duration.ofSeconds(30))
			.header("Zotero-API-Version", API_VERSION)
			.header("Accept", "application/json");
		if (write) {
			if (serverId != null) builder.header("Zotero-Server-ID", serverId);
			if (writeKey != null) builder.header("Zotero-API-Key", writeKey);
			if (versionPrecondition != null) builder.header(
				"If-Unmodified-Since-Version",
				versionPrecondition.toString()
			);
		}
		if ("POST".equals(method) || "PATCH".equals(method)
				|| "PUT".equals(method)) {
			builder.header("Content-Type", "application/json");
			builder.method(
				method,
				HttpRequest.BodyPublishers.ofString(body == null ? "" : body)
			);
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}

		HttpResponse<String> response;
		try {
			response = http
				.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		} catch (java.net.ConnectException e) {
			throw new ZoteroException(
					"the Zotero local API is not reachable at " + config.getBaseUrl()
							+ " — is Zotero running? (the local API is off by default; see the Zotero preferences)",
					e
			);
		} catch (Exception e) {
			if (e instanceof InterruptedException) Thread.currentThread()
				.interrupt();
			throw new ZoteroException(
					"could not talk to the Zotero local API at "
							+ config.getBaseUrl(),
					e
			);
		}
		captureHeaders(response);

		if (write && response.statusCode() == 401 && mayAuthorise) {
			authoriseWrite();
			return send(
				method,
				path,
				query,
				body,
				true,
				false,
				versionPrecondition
			);
		}
		if (write && response.statusCode() == 428 && mayAuthorise) {
			// the server wants its own id; take it from this very response and retry
			ensureServerId();
			return send(
				method,
				path,
				query,
				body,
				true,
				false,
				versionPrecondition
			);
		}
		return response;
	}

	/**
	 * Ask the user for permission to write. Zotero shows a dialog naming this application; the key it returns is single-use
	 * unless the user chose "Always Allow".
	 */
	private void authoriseWrite() {
		ensureServerId();
		ObjectNode body = mapper.createObjectNode();
		body.put("appName", ZoteroConfig.APP_NAME);
		HttpRequest request = HttpRequest
			.newBuilder(uri("/local/authorize", null))
			.timeout(Duration.ofSeconds(120))
			.header("Zotero-API-Version", API_VERSION)
			.header("Zotero-Server-ID", serverId)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
			.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (java.net.http.HttpTimeoutException e) {
			throw new ZoteroException(
					"waiting for the Zotero write permission dialog timed out after "
							+ "two minutes; approve the dialog in Zotero and ask again (nothing was written)",
					e
			);
		} catch (Exception e) {
			if (e instanceof InterruptedException) Thread.currentThread()
				.interrupt();
			throw new ZoteroException(
					"could not ask Zotero for write permission", e
			);
		}
		if (response.statusCode() == 403) {
			throw new ZoteroException(
					"the Zotero write request was declined — approve the dialog in Zotero to continue"
			);
		}
		if (response.statusCode() == 429) {
			throw new ZoteroException(
					"Zotero is limiting permission dialogs; wait a minute and try again"
			);
		}
		if (response.statusCode() != 200) {
			throw new ZoteroException(
					"Zotero refused write permission (HTTP " + response.statusCode()
							+ "): " + truncate(response.body())
			);
		}
		try {
			JsonNode authorisation = mapper.readTree(response.body());
			writeKey = authorisation.path("key")
				.asText(null);
			writeKeyRemembered = authorisation.path("remember")
				.asBoolean(false);
		} catch (Exception e) {
			throw new ZoteroException(
					"could not read the key Zotero returned for writes", e
			);
		}
		if (writeKey == null)
			throw new ZoteroException("Zotero returned no write key");
		log.info(
			"Zotero granted a {} write key",
			Boolean.TRUE.equals(writeKeyRemembered) ? "reusable (\"Always Allow\")"
				: "single-use: this request will succeed and the next one will ask again unless the user chooses \"Always Allow\""
		);
	}

	private void captureHeaders(HttpResponse<String> response) {
		response.headers()
			.firstValue("Zotero-API-Version")
			.ifPresent(v -> apiVersion = v);
		response.headers()
			.firstValue("Zotero-Schema-Version")
			.ifPresent(v -> schemaVersion = v);
		response.headers()
			.firstValue("Zotero-Server-ID")
			.ifPresent(v -> serverId = v);
		response.headers()
			.firstValue("Last-Modified-Version")
			.ifPresent(v -> {
				try {
					lastLibraryVersion = Long.parseLong(v.trim());
				} catch (NumberFormatException ignored) {
				}
			});
	}

	private void requireSuccess(HttpResponse<String> response, String what) {
		if (response.statusCode() >= 200 && response.statusCode() < 300) return;
		if (response.statusCode() == 403) {
			throw new ZoteroException(
					"Zotero refused " + what
							+ " (403). If this is a read, the local API "
							+ "preference is off: Zotero Settings → Advanced → \"Allow other applications on this "
							+ "computer to communicate with Zotero\""
			);
		}
		throw new ZoteroException(
				"Zotero returned HTTP " + response.statusCode() + " for " + what
						+ ": " + truncate(response.body())
		);
	}

	private URI uri(String path, String query) {
		String url = config.getBaseUrl()
				+ (path.startsWith("/") ? path : "/" + path);
		if (query != null && !query.isEmpty()) url = url + "?" + query;
		return URI.create(url);
	}

	/** one request, for callers that only want the side effect of capturing headers */
	private JsonNode request(
			String method, String path, String query, String body, boolean write,
			boolean mayAuthorise
	) {
		HttpResponse<String> response = send(
			method,
			path,
			query,
			body,
			write,
			mayAuthorise,
			null
		);
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
