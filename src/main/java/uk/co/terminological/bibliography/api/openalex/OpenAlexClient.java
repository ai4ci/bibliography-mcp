package uk.co.terminological.bibliography.api.openalex;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.time.LocalDate;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.isomorphism.util.TokenBuckets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import uk.co.terminological.bibliography.CachingApiClient;
import uk.co.terminological.bibliography.api.BibliographicApiException;
import uk.co.terminological.bibliography.client.CitedByMapper;
import uk.co.terminological.bibliography.client.CitesMapper;
import uk.co.terminological.bibliography.client.IdMapper;
import uk.co.terminological.bibliography.client.Query;
import uk.co.terminological.bibliography.client.RecordFetcher;
import uk.co.terminological.bibliography.client.SearchBuilder;
import uk.co.terminological.bibliography.client.Searcher;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordReferenceMapping;

/**
 * The OpenAlex REST API: a citation index and open-access locator over Crossref, PubMed, Europe PMC and the preprint
 * servers.
 *
 * https://developers.openalex.org/
 *
 * It is registered here as the source that answers the questions the others cannot: what cites a work, what a work
 * cites (for preprints, whose own server publishes no reference list), which identifiers name the same paper, and where
 * an open-access pdf lives. The last of those is why it replaces Unpaywall — the pdf link arrives in the same payload as
 * the metadata, so locating an open copy costs no second call and no second provider.
 *
 * <h2>Cost is the design constraint</h2>
 *
 * Unlike every other client in this project, OpenAlex meters by <em>credit</em> rather than by request: a lookup by
 * identifier is free, a filtered query costs 1, and anything involving text search costs 10, against a daily budget.
 * Hence the shapes below — batch everything (up to {@value #MAX_BATCH} ids per call), and keep search-class filters to
 * one per request. Responses carry the budget in {@code X-RateLimit-*} headers, which {@link #creditsRemaining()}
 * exposes so a crawl can stop on budget rather than on a call count.
 *
 * <h2>Authentication</h2>
 *
 * A key is sent as {@code Authorization: Bearer}, never as an {@code api_key} query parameter: both authenticate, but
 * the parameter would be folded into every cache key by {@link CachingApiClient#keyFromApiQuery}, splitting the cache
 * between authenticated and anonymous runs and writing a secret into the cache directory.
 */
public class OpenAlexClient extends CachingApiClient
		implements Searcher, RecordFetcher, IdMapper, CitesMapper, CitedByMapper {

	private static final Logger logger = LoggerFactory
		.getLogger(OpenAlexClient.class);

	private static String baseUrl = "https://api.openalex.org/";

	/** OpenAlex refuses an OR-filter with more than 100 values (400: "Maximum number of values exceeded for openalex"). */
	public static final int MAX_BATCH = 100;

	/** The largest response OpenAlex will return in one page. */
	public static final int MAX_PAGE = 200;

	/**
	 * shortcut: citation lists are paged to at most this many links per work, because a highly-cited work has tens of
	 * thousands and each page is 1 credit of a finite daily budget. Raise it, or make it a parameter, when a crawl needs
	 * whole inbound lists.
	 */
	private static final int MAX_LINKS_PER_WORK = 1000;

	/** What a text search costs, and the only tariff here big enough to need a gate. */
	public static final int SEARCH_COST = 10;

	/** The daily allowance on an authenticated account; a tenth of it without a key. */
	public static final long DEFAULT_DAILY_CREDITS = 10000;

	/**
	 * Credits held back from searching, so that a day of searches cannot leave a citation crawl or a batched fetch
	 * unable to make its 1-credit calls. 1,000 of a 10,000 allowance still permits ~900 searches a day, which is far more
	 * than an interactive session asks for.
	 */
	public static final long DEFAULT_SEARCH_CREDIT_RESERVE = 1000;

	private String apiKey;
	private String developerEmail;
	private ObjectMapper objectMapper = new ObjectMapper();
	private volatile Optional<Long> creditsRemaining = Optional.empty();
	private volatile long dailyCredits = DEFAULT_DAILY_CREDITS;
	private volatile long searchCreditReserve = DEFAULT_SEARCH_CREDIT_RESERVE;
	private final java.util.concurrent.atomic.AtomicLong creditsSpent = new java.util.concurrent.atomic.AtomicLong();

	private OpenAlexClient(
			Optional<Path> cachePath, String apiKey, String developerEmail
	) {
		super(
				cachePath, TokenBuckets.builder()
					// 100 credits a second is far more than the daily budget allows
					// anyway; this only stops one process hammering the API
					.withCapacity(10)
					.withInitialTokens(10)
					.withFixedIntervalRefillStrategy(10, 1, TimeUnit.SECONDS)
					.build()
		);
		this.apiKey = apiKey;
		this.developerEmail = developerEmail;
		// The shared 3 s read timeout is not enough: a full 200-record page measures
		// 2.4 s from here, so the default would fail the largest and most valuable
		// requests first, and a timeout here looks like "the API has nothing".
		this.setReadTimeout(8000);
		if (apiKey != null) {
			this.client.addFilter(
				new ClientFilter() {
					@Override
					public ClientResponse handle(ClientRequest request)
							throws ClientHandlerException {
						request.getHeaders()
							.add("Authorization", "Bearer " + apiKey);
						return getNext().handle(request);
					}
				}
			);
		}
	}

	private static OpenAlexClient singleton;

	public static OpenAlexClient create() {
		return create(null, null);
	}

	public static OpenAlexClient create(String apiKey, String developerEmail) {
		return create(apiKey, developerEmail, null);
	}

	public static OpenAlexClient create(
			String apiKey, String developerEmail, Path cacheDir
	) {
		if (singleton == null) {
			singleton = new OpenAlexClient(
					Optional.ofNullable(cacheDir), apiKey, developerEmail
			);
		}
		return singleton;
	}

	@Override
	protected MultivaluedMap<String, String> defaultApiParams() {
		MultivaluedMap<String, String> out = new MultivaluedMapImpl();
		// The polite-pool address. Not the key, which is a header: params are part of
		// the cache key.
		if (this.developerEmail != null) out.add("mailto", this.developerEmail);
		return out;
	}

	/**
	 * Record the credit budget; do not pace on it.
	 *
	 * {@code X-RateLimit-Remaining} is what is left of the daily allowance and {@code X-RateLimit-Limit} the whole of it.
	 * The inherited implementation reads Crossref's {@code X-Rate-Limit-*} headers, which OpenAlex does not send, so
	 * without this override the budget would go unseen.
	 *
	 * <p>The bucket in {@code CachingApiClient} paces <em>requests per second</em>, and the inherited override replaces it
	 * wholesale. Replacing it with the daily allowance here would let a process spend 10,000 credits in a burst — the
	 * allowance is a ceiling on a day's work, not a rate — so the pacing bucket built in the constructor is left alone and
	 * the budget is only reported, via {@link #creditsRemaining()}.
	 */
	@Override
	protected void updateRateLimits(MultivaluedMap<String, String> headers) {
		try {
			this.creditsRemaining = Optional.of(
					Long.parseLong(headers.getFirst("X-RateLimit-Remaining"))
			);
		} catch (Exception e) {
			// No budget header: whatever was seen last is kept
			logger.debug("No OpenAlex budget headers: {}", e.getMessage());
		}
	}

	/**
	 * Credits left in the account budget, as reported by the last response. Empty until a call has been made.
	 *
	 * Exposed so a crawl can stop when the budget runs out rather than when a call count does: an over-budget request is
	 * refused before it runs and costs nothing, so stopping early is free and continuing is not.
	 */
	public Optional<Long> creditsRemaining() { return this.creditsRemaining; }

	/**
	 * Set the day's allowance and the part of it searching may not touch.
	 *
	 * A budget is a day's worth, so setting one starts the day over: the local tally is cleared and the figure last
	 * reported by the API is forgotten, because neither describes the allowance just given.
	 *
	 * @param dailyCredits the ceiling, matching {@code openalex-daily-credits}
	 * @param searchCreditReserve credits kept back from searching for the 1-credit calls, matching
	 * {@code openalex-search-credit-reserve}
	 */
	public OpenAlexClient withBudget(
			long dailyCredits, long searchCreditReserve
	) {
		this.dailyCredits = dailyCredits;
		this.searchCreditReserve = searchCreditReserve;
		this.creditsSpent.set(0);
		this.creditsRemaining = Optional.empty();
		return this;
	}

	/** Credits this process has spent: what was paid for, cache hits excluded, since they are not requests. */
	public long creditsSpent() { return this.creditsSpent.get(); }

	/**
	 * Credits believed to be left today.
	 *
	 * The API's own figure when it has given one — the account is shared between processes and days, so our tally is
	 * only a stand-in until a response has spoken — and the ceiling less what this process has spent before that.
	 */
	public long creditsAvailable() {
		return this.creditsRemaining
			.orElseGet(() -> this.dailyCredits - this.creditsSpent.get());
	}

	/** Whether a call of this price can be paid for out of the ordinary budget. */
	public boolean canAfford(int cost) {
		return this.creditsAvailable() >= cost;
	}

	/**
	 * Whether a 10-credit search can be paid for without eating into the reserve.
	 *
	 * This is what makes OpenAlex usable as a search provider: searching is affordable at roughly 900 calls a day, and
	 * the reserve is what stops a day of searching from leaving the citation and fetch paths — the roles OpenAlex is the
	 * only source for — unable to make their 1-credit calls.
	 */
	public boolean canAffordSearch() {
		return this.creditsAvailable() - SEARCH_COST >= this.searchCreditReserve;
	}

	/**
	 * Count what a call costs as it is made.
	 *
	 * The cache is consulted before this point, so a cache hit never reaches here and never counts: the tally is credits
	 * actually spent, not requests the code asked for.
	 */
	@Override
	protected void rateLimit(int cost) {
		this.creditsSpent.addAndGet(cost);
		super.rateLimit(cost);
	}

	/**
	 * Refuse a response that cannot be a complete OpenAlex list, so that CachingApiClient treats the call as failed
	 * rather than caching a truncated body as a lasting "nothing found" (issue-004).
	 */
	private static <X> X complete(X parsed) {
		if (parsed instanceof OpenAlexListResult
				&& !((OpenAlexListResult<?>) parsed).isWellFormed())
			throw new BibliographicApiException(
					"incomplete OpenAlex response: no meta and no results"
			);
		return parsed;
	}

	private OpenAlexListResult.Works call(
			String path, MultivaluedMap<String, String> params, int cost
	) {
		return this
			.buildCall(baseUrl + path, OpenAlexListResult.Works.class)
			.withParams(params)
			.withOperation(
				is -> complete(
						new OpenAlexListResult.Works(this.objectMapper.readTree(is))
				)
			)
			.get(cost)
			.orElseGet(OpenAlexListResult.Works::empty);
	}

	// ####### References / identifiers ####### //

	/** An OpenAlex work id as a reference. Null or blank yields an incomplete reference rather than one named "null". */
	public static RecordReference workReference(String openAlexId) {
		ImmutableRecordReference.Builder builder = ImmutableRecordReference.builder()
			.identifierType(IdType.OPENALEX);
		if (openAlexId != null && !openAlexId.isEmpty()) {
			builder.identifier(
					openAlexId.substring(openAlexId.lastIndexOf('/') + 1)
			);
		}
		return builder.build();
	}

	private static String filterOf(IdType type) {
		switch (type) {
			case DOI:
				return "doi";
			case PMID:
				return "pmid";
			case PMCID:
				return "pmcid";
			case OPENALEX:
				return "ids.openalex";
			default:
				return null;
		}
	}

	/**
	 * Fetch a batch of works in as few calls as possible.
	 *
	 * One call per identifier type, 100 types' worth of ids per call, 1 credit for each however many ids it carries —
	 * batching is the whole economy of this API. A singleton by DOI or OpenAlex id is free, but the batched form is
	 * cheaper per id and is what every internal path uses.
	 */
	@Override
	public Map<ImmutableRecordReference, OpenAlexRecord> fetch(
			Collection<? extends RecordReference> equivalentIds
	) {
		Map<ImmutableRecordReference, OpenAlexRecord> out = new LinkedHashMap<>();
		Map<IdType, List<RecordReference>> byType = equivalentIds.stream()
			.filter(
				r -> r.getIdentifier()
					.isPresent() && filterOf(r.getIdentifierType()) != null
			)
			.collect(
				Collectors.groupingBy(
					RecordReference::getIdentifierType,
					LinkedHashMap::new,
					Collectors.toList()
				)
			);

		for (Map.Entry<IdType, List<RecordReference>> group : byType.entrySet()) {
			String filter = filterOf(group.getKey());
			List<List<RecordReference>> chunks = chunks(
					group.getValue(), MAX_BATCH
			);
			for (List<RecordReference> chunk : chunks) {
				MultivaluedMap<String, String> params = this.defaultApiParams();
				params.add(
						"filter",
						filter
							+ ":"
							+ chunk.stream()
								.map(r -> r.getIdentifier().get())
								.collect(Collectors.joining("|"))
				);
				params.add("per-page", Integer.toString(MAX_PAGE));
				// A chunk of n ids is one page of up to n works; MAX_BATCH is well
				// inside the page limit, so no paging is needed here.
				OpenAlexListResult.Works response = this.call(
						"works", params, 1
				);
				List<OpenAlexRecord> works = response.getItems()
					.collect(Collectors.toList());
				for (RecordReference asked : chunk) {
					ImmutableRecordReference key = asked.asId();
					works.stream()
						.filter(w -> names(w, asked))
						.findFirst()
						.ifPresent(w -> out.putIfAbsent(key, w));
				}
			}
		}
		return out;
	}

	/**
	 * Whether this work is the one asked for.
	 *
	 * The PMC id is compared without its prefix on both sides. OpenAlex publishes it as the bare URL slug
	 * ({@code .../articles/13141338}) while idconv, PubMed and Europe PMC publish it prefixed, so a caller
	 * may hand us either spelling: the API answers both, but a work fetched as {@code 13141338} has to match
	 * the answer carrying {@code PMC13141338} or the reply never reaches the key it was asked under.
	 */
	private static boolean names(OpenAlexRecord work, RecordReference asked) {
		if (asked.getIdentifierType() != IdType.PMCID) {
			ImmutableRecordReference wanted = asked.asId();
			return work.getIdentifiers()
				.stream()
				.anyMatch(held -> held.equals(wanted));
		}
		String slug = pmcSlug(asked.getIdentifier().get());
		return work.getIdentifiers()
			.stream()
			.anyMatch(
				held -> held.getIdentifierType() == IdType.PMCID
					&& pmcSlug(held.getIdentifier().orElse(""))
							.equals(slug)
			);
	}

	private static String pmcSlug(String id) {
		return id.startsWith("PMC") ? id.substring(3) : id;
	}

	private static <T> List<List<T>> chunks(List<T> in, int size) {
		List<List<T>> out = new ArrayList<>();
		for (int i = 0; i < in.size(); i += size) {
			out.add(in.subList(i, Math.min(in.size(), i + size)));
		}
		return out;
	}

	/**
	 * Which other identifiers name the same work.
	 *
	 * Free-form answer to the question the rest of the model depends on: a DOI, PMID, PMCID and OpenAlex id for one paper
	 * are four unrelated nodes until something says otherwise, and {@code BiblioFacade} deduplicates searches on exactly
	 * these equivalences. All of them are already in the work's own {@code ids} block, so this costs the fetch and
	 * nothing extra.
	 */
	@Override
	public Set<RecordReferenceMapping> mappings(
			Collection<? extends RecordReference> source
	) {
		Set<RecordReferenceMapping> out = new LinkedHashSet<>();
		this.fetch(source)
			.forEach(
				(key, record) -> record.getIdentifiers()
					.forEach(
						held -> out.add(
								Builder.recordReferenceMapping(key, held)
						)
					)
			);
		return out;
	}

	// ####### Citation graph ####### //

	/**
	 * What these works cite: the outgoing edges.
	 *
	 * {@code referenced_works} gives OpenAlex ids only, which are nodes in OpenAlex's graph rather than works a caller can
	 * name, so the batch is resolved to full records before the edges are built — one extra 1-credit call per 100 cited
	 * works, and the price of edges that join the ones Crossref and Europe PMC produce instead of sitting beside them.
	 */
	@Override
	public Collection<CitationLink> citesReferences(
			Collection<? extends RecordReference> sources
	) {
		Set<CitationLink> out = new LinkedHashSet<>();
		Map<ImmutableRecordReference, OpenAlexRecord> works = this.fetch(sources);

		List<RecordReference> allTargets = works.values()
			.stream()
			.flatMap(w -> w.getReferencedWorks()
				.stream())
			.distinct()
			.collect(Collectors.toList());
		// The referenced works come back as OpenAlex ids; resolving the batch gives edges
		// whose ends are works the caller can name, not just nodes in OpenAlex's graph
		Map<String, RecordReference> resolved = new HashMap<>();
		for (List<RecordReference> chunk : chunks(allTargets, MAX_BATCH)) {
			this.fetch(chunk)
				.forEach(
					(used, record) -> resolved.putIfAbsent(
							used.getIdentifier()
								.get(), record
					)
				);
		}

		works.forEach(
			(key, work) -> {
				Integer i = 1;
				for (RecordReference ref : work.getReferencedWorks()) {
					String id = ref.getIdentifier()
						.orElse(null);
					RecordReference target = resolved.getOrDefault(id, ref);
					out.add(Builder.citationLink(key, target, i));
					i += 1;
				}
			}
		);
		return out;
	}

	/**
	 * What cites these works: the incoming edges.
	 *
	 * Asking in this direction does not reverse anything — a {@link CitationLink} runs from the citing work to the cited
	 * one, so the work asked about is the <em>target</em> of everything returned (the convention that closed issue-007).
	 * Paged with a cursor, because an inbound list is open-ended in a way a reference list is not.
	 */
	@Override
	public Collection<CitationLink> referencesCiting(
			Collection<? extends RecordReference> sources
	) {
		Set<CitationLink> out = new LinkedHashSet<>();
		Map<ImmutableRecordReference, OpenAlexRecord> works = this.fetch(sources);

		for (Map.Entry<ImmutableRecordReference, OpenAlexRecord> entry : works.entrySet()) {
			ImmutableRecordReference asked = entry.getKey();
			String openAlexId = entry.getValue()
				.getIdentifier(IdType.OPENALEX)
				.orElse(null);
			if (openAlexId == null) continue;

			String cursor = "*";
			int collected = 0;
			while (cursor != null && collected < MAX_LINKS_PER_WORK) {
				MultivaluedMap<String, String> params = this.defaultApiParams();
				params.add("filter", "cites:" + openAlexId);
				params.add("per-page", Integer.toString(MAX_PAGE));
				params.add("cursor", cursor);
				OpenAlexListResult.Works page = this.call(
						"works", params, 1
				);
				List<OpenAlexRecord> items = page.getItems()
					.collect(Collectors.toList());
				if (items.isEmpty()) break;
				for (OpenAlexRecord citing : items) {
					out.add(Builder.citationLink(citing, asked, null));
				}
				collected += items.size();
				cursor = page.nextCursor()
					.filter(c -> !c.isEmpty() && !"null".equals(c))
					.orElse(null);
			}
		}
		return out;
	}

	// ####### Search ####### //

	@Override
	public SearchQueryBuilder buildQuery() {
		return new SearchQueryBuilder(this);
	}

	@Override
	public Collection<? extends Record> search(Query qry) {
		return this.buildQuery()
			.withQuery(qry)
			.execute()
			.map(r -> r.getItems()
				.collect(Collectors.toList()))
			.orElse(List.of());
	}

	/**
	 * OpenAlex's rendering of a {@link Query}.
	 *
	 * Terms become comma-separated filters inside one {@code filter} parameter, which AND together; repeating the
	 * parameter does not AND (measured: a second {@code filter=} is ignored, and the hit count runs to the millions), so
	 * there is exactly one here. Journal names cannot be filtered on directly — no {@code source.display_name} filter
	 * exists — so a name is resolved to an ISSN through the {@code sources} endpoint first, which costs a credit and is
	 * the only way to restrict by container rather than merely to bias towards it.
	 */
	public static class SearchQueryBuilder
			implements SearchBuilder<OpenAlexListResult<OpenAlexRecord>> {

		private final OpenAlexClient client;
		private final List<String> filters = new ArrayList<>();
		private String journalName = null;
		private int cost = 0;
		private Integer limit = null;

		protected SearchQueryBuilder(OpenAlexClient client) {
			this.client = client;
		}

		private SearchQueryBuilder filter(String expression, int creditCost) {
			if (expression != null && !expression.isEmpty()) {
				this.filters.add(expression);
				// A request is charged the price of its dearest filter, not the sum of them:
				// measured, a text search ANDed with an ISSN filter costs 10, not 11.
				this.cost = Math.max(this.cost, creditCost);
			}
			return this;
		}

		/**
		 * Terms matched anywhere the API indexes text.
		 *
		 * {@code default.search} <em>is</em> full text here — measured, it and a bare {@code search=} parameter return the
		 * identical count (674,724 for "photosynthesis") and the API describes both as "in the full text". It is used as a
		 * filter rather than as the parameter only because everything else in this request is a filter and one {@code filter}
		 * parameter has to carry all of them. The cost is recall over precision: a paper that merely mentions the term
		 * matches, which is what "anywhere the API indexes" asks for.
		 */
		public SearchQueryBuilder allFields(String term) {
			return this.filter("default.search:" + term, 10);
		}

		public SearchQueryBuilder titleAndAbstract(String term) {
			return this.filter("title_and_abstract.search:" + term, 10);
		}

		public SearchQueryBuilder author(String name) {
			return this.filter("raw_author_name.search:" + name, 10);
		}

		/**
		 * Restrict to a journal.
		 *
		 * OpenAlex filters works by ISSN, never by container name, so a name has to be looked up first. That lookup is a
		 * {@code /sources} text search, which costs 10 credits, so it is deferred to {@link #execute()} rather than paid for
		 * by whoever mentions a journal on the way to building a query. An ISSN passed straight through needs no lookup.
		 */
		public SearchQueryBuilder journal(String name) {
			if (name == null || name.isEmpty()) return this;
			if (ISSN.matcher(name)
				.matches()) {
				return this.filter(
						"primary_location.source.issn:" + OpenAlexClient.hyphenate(name),
						1
				);
			}
			this.journalName = name;
			this.cost = Math.max(this.cost, 10);
			return this;
		}

		/** A journal name awaiting ISSN resolution at execution time — the term is held, not dropped. */
		public Optional<String> journalName() {
			return Optional.ofNullable(this.journalName);
		}

		/**
		 * Restrict to a publication date window.
		 *
		 * Both bounds are optional and, unlike Entrez, OpenAlex honours a one-sided window, so an open-ended "since" is
		 * sent as it was asked rather than closed at today.
		 */
		public SearchQueryBuilder between(
				Optional<LocalDate> from, Optional<LocalDate> to
		) {
			from.ifPresent(d -> this.filter("from_publication_date:" + d, 1));
			to.ifPresent(d -> this.filter("to_publication_date:" + d, 1));
			return this;
		}

		public SearchQueryBuilder limit(Optional<Integer> records) {
			records.ifPresent(r -> this.limit = r > MAX_PAGE ? MAX_PAGE : r);
			return this;
		}

		@Override
		public SearchQueryBuilder withQuery(Query qry) {
			qry.getNativeQuery()
				.ifPresent(n -> this.filter(n, 0));
			qry.getAllFields()
				.ifPresent(this::allFields);
			qry.getTitleAbstract()
				.ifPresent(this::titleAndAbstract);
			qry.getFullText()
				.forEach(this::allFields);
			qry.getAuthors()
				.forEach(this::author);
			qry.getJournal()
				.ifPresent(this::journal);
			this.between(qry.getAfter(), qry.getUntil());
			this.limit(qry.getLimit());
			return this;
		}

		/**
		 * The filters assembled so far, joined as one {@code filter} parameter.
		 *
		 * Does not include a journal name awaiting ISSN resolution — that lookup happens in {@link #execute()}, so a test
		 * asserting this can never spend credits.
		 */
		public String filter() {
			return String.join(",", this.filters);
		}

		/** Credits this request will spend, so a caller can see a 10-credit search coming. */
		public int cost() { return this.cost; }

		/** The page size this request would ask for. */
		public int perPage() {
			return this.limit == null ? MAX_PAGE : this.limit;
		}

		@Override
		public Optional<OpenAlexListResult<OpenAlexRecord>> execute() {
			if (this.filters.isEmpty() && this.journalName == null) return Optional.empty();
			// Refuse by name rather than by returning nothing. An over-budget request
			// is refused by the API before it runs, and CachingApiClient turns every
			// non-2xx into an empty Optional (issue-013), so without this a caller
			// cannot tell an exhausted budget from a search that found no papers.
			if (this.cost >= SEARCH_COST && !this.client.canAffordSearch())
				throw new BibliographicApiException(
						"OpenAlex search refused: " + this.cost
								+ " credits needed, " + this.client.creditsAvailable()
								+ " available and "
								+ this.client.searchCreditReserve
								+ " reserved for fetch and citation calls"
				);
			if (!this.client.canAfford(this.cost))
				throw new BibliographicApiException(
						"OpenAlex call refused: " + this.cost + " credits needed, "
								+ this.client.creditsAvailable() + " available"
				);
			// The journal name is resolved here rather than in journal() so that building a
			// query costs nothing; the lookup is a 10 credit search
			if (this.journalName != null) {
				this.client.findIssn(this.journalName)
					.ifPresent(
							issn -> this.filters.add(
									"primary_location.source.issn:" + issn
								)
					);
			}
			if (this.filters.isEmpty()) return Optional.empty();
			MultivaluedMap<String, String> params = this.client.defaultApiParams();
			params.add("filter", this.filter());
			params.add(
					"per-page",
					Integer.toString(this.limit == null ? MAX_PAGE : this.limit)
			);
			return Optional.of(
					this.client.call("works", params, this.cost)
			);
		}
	}

	/** An ISSN, hyphenated: the form the works filter wants. The check digit can be X. */
	private static final java.util.regex.Pattern ISSN = java.util.regex.Pattern
		.compile("\\d{4}-?\\d{3}[0-9Xx]");

	private static String hyphenate(String issn) {
		String bare = issn.replace("-", "");
		return bare.substring(0, 4) + "-" + bare.substring(4);
	}

	/**
	 * The ISSN of a journal given by name, or empty if OpenAlex does not know it.
	 *
	 * Costs 10 credits when the name has to be text-searched ({@code /sources?search=}); an exact ISSN is confirmed for 1.
	 */
	Optional<String> findIssn(String journalName) {
		if (journalName == null || journalName.isEmpty()) return Optional.empty();
		if (ISSN.matcher(journalName)
			.matches()) {
			return Optional.of(hyphenate(journalName));
		}
		MultivaluedMap<String, String> params = this.defaultApiParams();
		params.add("search", journalName);
		params.add("per-page", "1");
		return this
			.buildCall(baseUrl + "sources", OpenAlexListResult.Sources.class)
			.withParams(params)
			.withOperation(
				is -> new OpenAlexListResult.Sources(this.objectMapper.readTree(is))
			)
			.get(10)
			.flatMap(r -> r.getItems()
				.findFirst())
			.flatMap(s -> s.asString("issn_l"))
			.map(OpenAlexClient::hyphenate);
	}

}
