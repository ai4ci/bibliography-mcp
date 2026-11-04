package uk.co.terminological.bibliography;

import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response.Status;

import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.MemoryUnit;
import org.isomorphism.util.TokenBucket;
import org.isomorphism.util.TokenBuckets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.filter.LoggingFilter;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import uk.co.terminological.bibliography.api.BibliographicApiException;
import uk.co.terminological.datatypes.StreamExceptions;
import uk.co.terminological.datatypes.StreamExceptions.FunctionWithException;

public abstract class CachingApiClient {

	protected CacheManager cacheManager;
	protected boolean debug = false;
	protected boolean noCache = false;

	/**
	 * How long to wait for an answer before giving up on a call.
	 *
	 * Deliberately generous. The old default was three seconds, which is comfortable for most bibliographic APIs and
	 * wrong for several of them — OpenCitations, {@code api.biorxiv.org} and the arXiv API all answer in 3-6 seconds —
	 * and being wrong is silent: every call times out, the API looks like it has no data, and the symptom is an empty
	 * result set rather than an error. A client that is slower still raises its own with {@link #setReadTimeout(int)},
	 * which is a thing to remember, but forgetting it now costs latency rather than correctness (debt-009).
	 */
	public static final int DEFAULT_READ_TIMEOUT_MS = 15_000;

	/** How long to wait for the connection itself. A host that will not talk to us at all is a different failure. */
	public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

	/**
	 * The longest a "permanent" answer is served before being asked again.
	 *
	 * The cache named {@code forever} used not to expire at all, so a successful-but-wrong answer — an upstream gap at
	 * fetch time — was pinned for the life of the directory, and the only cure was a human deleting it. Bibliographic
	 * metadata does not change often enough to want a short life, but nothing should be permanent (debt-006).
	 */
	private static final Duration LONG_LIVED = Duration.ofDays(180);

	protected CachingApiClient(
			Optional<Path> optional, TokenBucket ratelimiter
	) {
		this.client = Client.create();
		this.client.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
		this.client.setReadTimeout(DEFAULT_READ_TIMEOUT_MS);
		this.cacheManager = openCache(optional);
		Runtime.getRuntime()
			.addShutdownHook(new Thread() {
				@Override
				public void run() {
					logger.info(
						"Closing cache: " + optional.map(Path::toString)
							.orElse("memory")
					);
					if (CachingApiClient.this.cacheManager.getStatus()
						.equals(org.ehcache.Status.AVAILABLE)) {
						CachingApiClient.this.cacheManager.close();
					}
				}
			});
		this.rateLimiter = ratelimiter;
	}

	/**
	 * Open the cache, rebuilding it from empty if this build cannot read what is already there.
	 *
	 * A persistent EHCache store is written with Java serialization, so any change to the shape of {@link BinaryData}
	 * changes its computed {@code serialVersionUID} and makes every existing entry unreadable. That used to be
	 * <em>fatal</em>: {@code init()} threw, the exception surfaced from {@code BibliographicApis.create}, and the server
	 * would not start until someone found and deleted a cache directory — a configuration-shaped error with a
	 * housekeeping cause (debt-007).
	 *
	 * The cache is disposable: everything in it can be fetched again. So an unreadable one is cleared and recreated,
	 * loudly, and the only cost is the saved calls.
	 */
	private static CacheManager openCache(Optional<Path> dir) {
		if (dir.isPresent())
			StreamExceptions.tryRethrow(t -> Files.createDirectories(dir.get()));
		var manager = cacheManagerFor(dir);
		try {
			manager.init();
			return manager;
		} catch (RuntimeException e) {
			// an in-memory cache has nothing stale to blame, so a failure there is real
			if (dir.isEmpty()) throw e;
			logger.warn(
				"Could not open the cache at {} ({}); clearing and rebuilding it."
						+ " The cache is disposable — nothing is lost but the calls it saved.",
				dir.get(),
				e.toString()
			);
			try {
				manager.close();
			} catch (RuntimeException ignored) {
				// a half-initialised manager may refuse to close; the lock files go with the directory
			}
			empty(dir.get());
			var rebuilt = cacheManagerFor(dir);
			rebuilt.init();
			return rebuilt;
		}
	}

	private static CacheManager cacheManagerFor(Optional<Path> dir) {
		if (dir.isPresent()) return CacheManagerBuilder.newCacheManagerBuilder()
			.with(
				CacheManagerBuilder.persistence(
					dir.get()
						.toFile()
				)
			)
			.withCache(
				"forever",
				configure(
					ResourcePoolsBuilder.heap(10000)
						.disk(400, MemoryUnit.MB, true),
					LONG_LIVED
				)
			)
			.withCache(
				"week",
				configure(
					ResourcePoolsBuilder.heap(1000)
						.disk(40, MemoryUnit.MB, true),
					Duration.ofDays(7)
				)
			)
			.build();
		return CacheManagerBuilder.newCacheManagerBuilder()
			.withCache(
				"forever",
				configure(ResourcePoolsBuilder.heap(10000), LONG_LIVED)
			)
			.withCache(
				"week",
				configure(ResourcePoolsBuilder.heap(10000), Duration.ofDays(7))
			)
			.build();
	}

	private static CacheConfigurationBuilder<String, BinaryData> configure(
			ResourcePoolsBuilder pools, Duration life
	) {
		return CacheConfigurationBuilder
			.newCacheConfigurationBuilder(String.class, BinaryData.class, pools)
			.withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(life));
	}

	/** Remove everything under a cache directory, leaving the directory itself. */
	private static void empty(Path dir) {
		StreamExceptions.<Path, Exception>tryRethrow(dir, d -> {
			try (var walk = Files.walk(d)) {
				for (Path p : walk.sorted(java.util.Comparator.reverseOrder())
					.collect(Collectors.toList())) {
					if (!p.equals(d)) Files.deleteIfExists(p);
				}
			}
		});
	}

	/**
	 * Whether a response body is worth keeping.
	 *
	 * An empty or blank body carries nothing a caller can use, and caching one turns a momentary upstream gap into a
	 * lasting "no data" for that request (debt-006). It is cheaper to ask again than to be permanently wrong.
	 *
	 * Public for testing.
	 */
	public static boolean worthCaching(byte[] body) {
		if (body == null || body.length == 0) return false;
		for (byte b : body) {
			if (b < 0 || !Character.isWhitespace(b)) return true;
		}
		return false;
	}

	protected Cache<String, BinaryData> permanentCache() {
		return this.cacheManager
			.getCache("forever", String.class, BinaryData.class);
	}

	protected Cache<String, BinaryData> tempCache() {
		return this.cacheManager.getCache("week", String.class, BinaryData.class);
	}

	public void shutdown() {
		if (this.cacheManager.getStatus()
			.equals(org.ehcache.Status.AVAILABLE)) {
			this.cacheManager.close();
		}
	}

	public void disableCache() {
		this.noCache = true;
	}

	public void enableCache() {
		this.noCache = false;
	}

	public void debugMode() {
		this.debug = true;
		this.client.addFilter(
			new LoggingFilter(new java.util.logging.Logger("Jersey", null) {
				@Override
				public void info(String msg) {
					logger.info(msg);
				}
			})
		);
	}

	private static final Logger logger = LoggerFactory
		.getLogger(CachingApiClient.class);
	protected Client client;
	/**
	 * The bucket that paces this client. Protected so a client can replace it with the budget the
	 * API actually reports (OpenAlex meters credits rather than requests).
	 */
	protected TokenBucket rateLimiter;

	/**
	 * Raise the read timeout for an API that answers slowly.
	 *
	 * The shared default is deliberately short — most bibliographic APIs answer in well under a second, and one hung
	 * request should not stall a batch — but several take 3-6 seconds (OpenCitations, api.biorxiv.org, the arXiv API). A
	 * client that forgets this does not fail loudly: every call times out, the API looks like it has no data, and the first
	 * symptom is an empty result set.
	 */
	protected void setReadTimeout(int millis) {
		this.client.setReadTimeout(millis);
		this.readTimeoutMs = millis;
	}

	/** what {@link #setReadTimeout(int)} last set, so a timeout can say what it waited for */
	private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

	/** Whether this failure was us running out of patience rather than the API answering badly. */
	private static boolean timedOut(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof java.net.SocketTimeoutException) return true;
		}
		return false;
	}

	protected void rateLimit(int cost) {
		this.rateLimiter.consume(cost);
	}

	/**
	 * if the current capacity is less 10% of normal capacity we may change to a different api.
	 * 
	 * @return
	 */
	protected boolean throttle() {
		return ((double) this.rateLimiter.getNumTokens())
				/ this.rateLimiter.getCapacity() < 0.1D;
	}

	/**
	 * Whether a response body is shorter than the length the server declared. A truncation is invisible to a JSON parser (a
	 * prefix of a document can be valid JSON), so it is checked before the body is parsed and cached — see issue-004.
	 *
	 * Returns false when no comparable length is available: no Content-Length (for example a chunked response) or a body
	 * that was decoded, where the received length legitimately differs from the encoded one.
	 *
	 * Public for testing.
	 */
	public static boolean isTruncated(
			String declaredLength, String contentEncoding, int actualLength
	) {
		if (contentEncoding != null && !contentEncoding.trim()
			.isEmpty() && !"identity".equalsIgnoreCase(contentEncoding.trim()))
			return false;
		if (declaredLength == null || declaredLength.trim()
			.isEmpty()) return false;
		try {
			return Long.parseLong(declaredLength.trim()) != actualLength;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	protected static String encode(String string) {
		try {
			return URLEncoder
				.encode(string, java.nio.charset.StandardCharsets.UTF_8.toString());
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	protected static String keyFromApiQuery(
			String url, MultivaluedMap<String, String> params
	) {
		return "{\"url\":\"" + url + "\",\"params\":{" + params.keySet()
			.stream()
			.sorted()
			.map(
				t -> "\"" + t + "\":[" + params.get(t)
					.stream()
					.sorted()
					.map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
					.collect(Collectors.joining(",")) + "]"
			)
			.collect(Collectors.joining(",")) + "}}";
	}

	protected void updateRateLimits(MultivaluedMap<String, String> headers) {
		try {
			Long rateLimitRequests = Long.parseLong(
				headers.get("X-Rate-Limit-Limit")
					.get(0)
			);
			Long rateLimitInterval = Long.parseLong(
				headers.get("X-Rate-Limit-Interval")
					.get(0)
					.replace("s", "")
			);
			this.rateLimiter = TokenBuckets.builder()
				.withInitialTokens(this.rateLimiter.getNumTokens())
				.withCapacity(rateLimitRequests)
				.withFixedIntervalRefillStrategy(
					rateLimitRequests,
					rateLimitInterval,
					TimeUnit.SECONDS
				)
				.build();
		} catch (Exception e) {
			// Probably header wasn't set - just ignore
		}
	}

	protected abstract MultivaluedMap<String, String> defaultApiParams();

	protected <X> CallBuilder<X, Exception> buildCall(
			String url, Class<X> clazz
	) {
		return new CallBuilder<>(url, this.defaultApiParams(), this);
	}

	protected static class CallBuilder<X, E extends Exception> {
		String url;
		MultivaluedMap<String, String> params;
		FunctionWithException<InputStream, X, E> operation;
		boolean temporary = true;
		CachingApiClient client;
		CallBuilder(
				String url, MultivaluedMap<String, String> params,
				CachingApiClient client
		) {
			this.url = url;
			this.params = params;
			this.client = client;
		}
		public CallBuilder<X, E> withParam(String key, String value) {
			this.params.add(key, value);
			return this;
		}
		public CallBuilder<X, E> withParams(
				MultivaluedMap<String, String> params
		) {
			this.params = params;
			return this;
		}
		public CallBuilder<X, E> withOperation(
				FunctionWithException<InputStream, X, E> operation
		) {
			this.operation = operation;
			return this;
		}
		public CallBuilder<X, E> cacheForever() {
			this.temporary = false;
			return this;
		}
		public Optional<X> get() {
			return this.get(1);
		}
		public Optional<X> get(int cost) {
			return this.client.call(
				this.url,
				this.params,
				this.temporary,
				"GET",
				this.operation,
				null,
				cost
			);
		}
		public Optional<X> post(MediaType encoding) {
			return this.post(encoding, 1);
		}
		public Optional<X> post(MediaType encoding, int cost) {
			return this.client.call(
				this.url,
				this.params,
				this.temporary,
				"POST",
				this.operation,
				encoding,
				cost
			);
		}
		public CallBuilder<X, E> collapseLists(String string) {
			MultivaluedMap<String, String> tmp = new MultivaluedMapImpl();
			this.params.forEach(
				(k, v) -> tmp.putSingle(
					k,
					v.stream()
						.collect(Collectors.joining(string))
				)
			);
			this.params = tmp;
			return this;
		}
	}

	private <X, E extends Exception> Optional<X> call(
			String url, MultivaluedMap<String, String> params, boolean temporary,
			String method, FunctionWithException<InputStream, X, E> operation,
			MediaType encoding, int cost
	) {
		if (operation == null)
			throw new NullPointerException("Operation must be defined");
		var cache = temporary ? this.tempCache() : this.permanentCache();
		var key = keyFromApiQuery(url, params);
		if (cache.containsKey(key)) {
			if (!this.noCache) {
				logger.debug("Cache hit:" + key);
				try {
					var out = operation.apply(
						cache.get(key)
							.inputStream()
					);
					return Optional.of(out);
				} catch (Exception e) {
					if (this.debug) { e.printStackTrace(); }
					logger.debug("Could not parse cached result:" + key);
					cache.remove(key);
				}
			} else {
				logger.debug("Removing disabled cache item:" + key);
				cache.remove(key);
			}
		}
		// Not cached or operation did not succeed
		this.rateLimit(cost);
		logger.trace("Retrieving from API: " + key);
		var wr = this.client.resource(url);
		try {

			ClientResponse r;
			if ("POST".equalsIgnoreCase(method)) {
				r = wr.type(encoding)
					.post(ClientResponse.class, params);
			} else {
				r = wr.queryParams(params)
					.method(method, ClientResponse.class);
			}

			logger.debug("Calling url: {}", wr.toString());
			this.updateRateLimits(r.getHeaders());
			if (!r.getClientResponseStatus()
				.getFamily()
				.equals(Status.Family.SUCCESSFUL)) {
				// WARN, not debug: this empty Optional is indistinguishable from "the
				// API has nothing", so without a line here a rate limit, a 5xx or a
				// moved endpoint all read as "no results" (issue-013, debt-013). The
				// contract stays exception-free; the silence does not.
				logger.warn(
					"API call failed with status {} : {}",
					r.getClientResponseStatus(),
					wr.toString()
				);
				return Optional.empty();
			}
			var data = BinaryData.from(r.getEntityInputStream());
			// byteArray is read directly rather than through an accessor: adding a
			// method to BinaryData changes its computed serialVersionUID and would
			// invalidate every persisted cache entry (see debt-007)
			if (isTruncated(
				r.getHeaders()
					.getFirst("Content-Length"),
				r.getHeaders()
					.getFirst("Content-Encoding"),
				data.byteArray.length
			)) {
				// deliberately not cached: a short read is a failed call, and caching
				// it would turn a transient fault into a lasting wrong answer
				logger.warn(
					"Truncated API response from {}: read {} bytes, Content-Length {}",
					wr.toString(),
					data.byteArray.length,
					r.getHeaders()
						.getFirst("Content-Length")
				);
				return Optional.empty();
			}
			var out = operation.apply(data.inputStream());
			if (worthCaching(data.byteArray)) {
				cache.put(key, data);
			} else {
				// an empty body is a gap, not an answer, and caching it would make the
				// gap permanent for this request (debt-006)
				logger.warn("Empty response body from {}; not cached", wr.toString());
			}
			return Optional.of(out);
		} catch (Exception e) {
			if (this.debug) { e.printStackTrace(); }
			if (timedOut(e)) {
				// said separately because it is not a parse failure and not an empty API:
				// it is this client being too impatient for this one (debt-009)
				logger.warn(
					"API call to {} timed out after {}ms; raise this client's read timeout"
							+ " if it is always this slow",
					wr.toString(),
					this.readTimeoutMs
				);
			} else {
				logger.warn(
					"Could not parse API result {} because of {}",
					wr.toString(),
					e.getMessage()
				);
			}
			return Optional.empty();
		}
	}

	protected Optional<InputStream> cachedStream(
			String key, boolean temporary,
			FunctionWithException<String, InputStream, Exception> supplier
	) {
		var cache = temporary ? this.tempCache() : this.permanentCache();
		if (cache.containsKey(key)) {
			logger.debug("Cache hit:" + key);
			return Optional.of(
				cache.get(key)
					.inputStream()
			);
		}
		try {
			var data = BinaryData.from(supplier.apply(key));
			if (worthCaching(data.byteArray)) cache.put(key, data);
			return Optional.of(data.inputStream());
		} catch (Exception e) {
			if (this.debug) { e.printStackTrace(); }
			logger.debug("Could not open input stream: " + key);
			return Optional.empty();
		}

	}

	// TODO: A raw filesystem cache so that we can see the cache result - maybe alongside ehcache result.
	protected Optional<String> cachedString(
			String key, boolean temporary,
			FunctionWithException<String, String, Exception> supplier
	) {
		var cache = temporary ? this.tempCache() : this.permanentCache();
		if (cache.containsKey(key)) {
			logger.debug("Cache hit:" + key);
			return Optional.of(
				cache.get(key)
					.toString()
			);
		}
		try {
			var data = BinaryData.from(supplier.apply(key));
			if (worthCaching(data.byteArray)) cache.put(key, data);
			return Optional.of(data.toString());
		} catch (Exception e) {
			if (this.debug) { e.printStackTrace(); }
			logger.debug("Could get input string: " + key);
			return Optional.empty();
		}
	}

	// TODO: A raw filesystem cache so that we can see the cache result - maybe alongside ehcache result.
	protected <X extends Serializable> Optional<X> cachedObject(
			String key, boolean temporary,
			FunctionWithException<String, X, Exception> supplier
	) {
		var cache = temporary ? this.tempCache() : this.permanentCache();
		if (cache.containsKey(key)) {
			logger.debug("Cache hit:" + key);
			try {
				return Optional.of(
					cache.get(key)
						.toObject()
				);
			} catch (BibliographicApiException e) {
				cache.remove(key);
			}
		}
		try {
			var data = BinaryData.from(supplier.apply(key));
			if (worthCaching(data.byteArray)) cache.put(key, data);
			return Optional.of(data.toObject());
		} catch (Exception e) {
			if (this.debug) { e.printStackTrace(); }
			logger.debug("Could get input string: " + key);
			return Optional.empty();
		}

	}
}