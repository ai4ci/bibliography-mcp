package uk.co.terminological.bibliography.api.opencitations;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.ws.rs.core.MultivaluedMap;

import org.isomorphism.util.TokenBuckets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import uk.co.terminological.bibliography.CachingApiClient;
import uk.co.terminological.bibliography.client.CitedByMapper;
import uk.co.terminological.bibliography.client.CitesMapper;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.RecordReference;

public class OpenCitationsClient extends CachingApiClient
		implements CitedByMapper, CitesMapper {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory
		.getLogger(OpenCitationsClient.class);


	protected OpenCitationsClient(Optional<Path> optional) {
		super(
				optional, TokenBuckets.builder()
					.withCapacity(3)
					.withInitialTokens(3)
					.withFixedIntervalRefillStrategy(3, 1, TimeUnit.SECONDS)
					.build()
		);
		// Docs: rate-limited to 180 requests/minute per IP (api.opencitations.net/index/v2).
		// this API responds in 3-6s, and occasionally much worse on a large citation
		// set, so it asks for more than the shared default
		this.setReadTimeout(30_000);
	}

	// ponytail: old opencitations.net/index/api/v1 endpoint now 301-redirects; Jersey 1.12
	// doesn't follow — use the canonical documented api.opencitations.net/index/v2 endpoint
	static String baseUrl = "https://api.opencitations.net/index/v2/";
	static OpenCitationsClient singleton;

	private ObjectMapper objectMapper = new ObjectMapper();

	@Override
	protected MultivaluedMap<String, String> defaultApiParams() {
		return new MultivaluedMapImpl();
	}
	// https://opencitations.net/index/api/v1

	public static OpenCitationsClient create() {
		if (singleton == null)
			singleton = new OpenCitationsClient(Optional.empty());
		return singleton;
	}

	public static OpenCitationsClient create(Path cacheDir) {
		if (singleton == null)
			singleton = new OpenCitationsClient(Optional.ofNullable(cacheDir));
		return singleton;
	}

	public List<String> getReferencingDoisByDoi(String doi) {
		// https://w3id.org/oc/index/api/v1/citations/10.1002/adfm.201505328
		return this.buildQuery(Action.CITATIONS, doi)
			.execute()
			.stream()
			.flatMap(lr -> lr.getCitations())
			.flatMap(
				c -> c.getCitingDoi()
					.stream()
			)
			.collect(Collectors.toList());
	}

	public List<String> getReferencedDoisByDoi(String doi) {
		// https://w3id.org/oc/index/api/v1/references/10.1186/1756-8722-6-59
		return this.buildQuery(Action.REFERENCES, doi)
			.execute()
			.stream()
			.flatMap(lr -> lr.getCitations())
			.flatMap(
				c -> c.getCitedDoi()
					.stream()
			)
			.collect(Collectors.toList());
	}

	public QueryBuilder buildQuery(Action action, String doi) {
		QueryBuilder out = new QueryBuilder(
				baseUrl + action.name()
					.toLowerCase(),
				doi, defaultApiParams(), this
		);
		return out;
	}

	public static enum Action { CITATIONS, REFERENCES }

	public static class QueryBuilder {
		protected String url;

		MultivaluedMap<String, String> params;
		// SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
		OpenCitationsClient client;

		/*
		 * ivate WebResource get(Client client) { WebResource tdmCopy = client.resource(url); tdmCopy =
		 * tdmCopy.queryParams(params); return tdmCopy; }
		 */

		private QueryBuilder(
				String url, String doi,
				MultivaluedMap<String, String> defaultParams,
				OpenCitationsClient client
		) {
			// v2 requires a scheme prefix on the id (doi:, pmid:, omid:) — client passes bare DOIs
			String pid = doi.contains(":") ? doi : "doi:" + doi;
			this.url = url + "/" + encode(pid);
			this.params = defaultParams;
			this.client = client;

		}

		public QueryBuilder withExclusions(Fields... fields) {
			this.params.put(
				"exclude",
				Arrays.stream(fields)
					.map(
						f -> f.toString()
							.toLowerCase()
					)
					.collect(Collectors.toList())
			);
			return this;
		}

		public QueryBuilder withFilter(Map<Fields, String> fieldMap) {
			this.params.put(
				"filter",
				fieldMap.entrySet()
					.stream()
					.map(
						f -> f.getKey()
							.toString()
							.toLowerCase() + ":" + f.getValue()
					)
					.collect(Collectors.toList())
			);
			return this;
		}

		public QueryBuilder withSortAscending(Fields field) {
			this.params.add(
				"sort",
				"asc(" + field.toString()
					.toLowerCase() + ")"
			);
			return this;
		}

		public QueryBuilder withSortDescending(Fields field) {
			this.params.add(
				"sort",
				"desc(" + field.toString()
					.toLowerCase() + ")"
			);
			return this;
		}

		// always get json and dont allow jsonp

		public String toString() {
			return keyFromApiQuery(url, params);
		}

		public Optional<OpenCitationsListResult> execute() {
			return client.buildCall(url, OpenCitationsListResult.class)
				.withParams(params)
				.withOperation(
					is -> new OpenCitationsListResult(
							client.objectMapper.readTree(is)
					)
				)
				.get();
		}
	}

	public static enum Fields {
		oci, citing, cited, creation, timespan, journal_sc, author_sc;
	}

	/**
	 * The schemes OpenCitations answers to, measured against the live API rather than taken from the documentation.
	 *
	 * {@code doi}, {@code pmid} and {@code isbn} return results; {@code pmcid} and {@code openalex} return
	 * <strong>400</strong>, so asking about one spends a call to be refused. {@code omid} works too but this project
	 * has no id type for it, so nothing can ask about one.
	 */
	private static final Map<IdType, String> ACCEPTED_SCHEMES = Map.of(
		IdType.DOI, "doi:", IdType.PMID, "pmid:", IdType.ISBN, "isbn:"
	);

	/**
	 * How to ask OpenCitations about this reference, or empty when it cannot be asked.
	 *
	 * The client used to query DOIs alone, so a record known only by its PMID got no citations and no explanation —
	 * debt-002. OpenCitations answers to a PMID perfectly well.
	 *
	 * Visible for testing: which identifiers are askable is a fact about the API and worth pinning without a call.
	 */
	static Optional<String> queryIdFor(RecordReference ref) {
		var scheme = ACCEPTED_SCHEMES.get(ref.getIdentifierType());
		if (scheme == null) return Optional.empty();
		return ref.getIdentifier()
			.filter(id -> !id.isBlank())
			.map(id -> scheme + id);
	}

	/** the references this API can be asked about, saying in the log what it cannot */
	private List<String> askableIds(Collection<? extends RecordReference> refs) {
		List<String> out = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (RecordReference ref : refs) {
			var asked = queryIdFor(ref);
			if (asked.isPresent()) out.add(asked.get());
			else skipped.add(
				ref.getIdentifierType() + ":"
						+ ref.getIdentifier()
							.orElse("?")
			);
		}
		if (!skipped.isEmpty()) LOG.info(
			"OpenCitations cannot be asked about {} of {} references ({}); it"
					+ " answers to doi, pmid and isbn only",
			skipped.size(),
			refs.size(),
			skipped.size() > 5 ? skipped.subList(0, 5) + "…" : skipped
		);
		return out;
	}

	@Override
	public Set<? extends CitationLink> citesReferences(
			Collection<? extends RecordReference> ref
	) {
		Set<CitationLink> out = new HashSet<>();
		askableIds(ref).forEach(
			id -> this.buildQuery(Action.REFERENCES, id)
				.execute()
				.stream()
				.flatMap(lr -> lr.getCitations())
				.forEach(e -> out.add(e))
		);
		return out;
	}

	@Override
	public Collection<? extends CitationLink> referencesCiting(
			Collection<? extends RecordReference> ref
	) {
		List<CitationLink> out = new ArrayList<>();
		askableIds(ref).forEach(
			id -> this.buildQuery(Action.CITATIONS, id)
				.execute()
				.stream()
				.flatMap(lr -> lr.getCitations())
				.forEach(e -> out.add(e))
		);
		return out;
	}
}
