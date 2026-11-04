package uk.co.terminological.bibliography.api.medrxiv;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.ws.rs.core.MultivaluedMap;

import org.isomorphism.util.TokenBuckets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import uk.co.terminological.bibliography.CachingApiClient;
import uk.co.terminological.bibliography.client.CitesMapper;
import uk.co.terminological.bibliography.client.RecordFetcher;
import uk.co.terminological.bibliography.jats.JatsEntry;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.fluentxml.Xml;

/**
 * medRxiv and bioRxiv, through `api.biorxiv.org`.
 *
 * Two jobs, and the second is the interesting one:
 *
 * - **fetch**: `/details/{server}/{doi}` gives every version of a preprint, with the server's own metadata (version,
 * license, category, funder, the `published` link). - **cites**: the details response carries a `jatsxml` URL, and the
 * article XML contains the reference list. That is the only way to get the references of a preprint that Europe PMC
 * does not index — which it often does not: coverage is partial. For preprints Europe PMC *does* index, it is the
 * better source, and the merge priority says so.
 *
 * Only DOIs under the bioRxiv/medRxiv prefix `10.1101/` are looked at, so adding this client to the default citation
 * expansion costs nothing for ordinary journal DOIs.
 *
 * [IMPLEMENTS](/design/features/feat-009-preprint-clients.md)
 */
public class MedrxivClient extends CachingApiClient
		implements RecordFetcher, CitesMapper {

	static Logger log = LoggerFactory.getLogger(MedrxivClient.class);

	private static final String API = "https://api.biorxiv.org";
	private static final String SEARCH = "https://www.biorxiv.org/search/";
	private static final String[] PREFIX = { "10.1101/", "10.64898/" };
	private static final List<String> SERVERS = List.of("medrxiv", "biorxiv");
	private static final String USER_AGENT = "bibliographic-api-client/1.0";

	private final ObjectMapper mapper = new ObjectMapper();
	private static MedrxivClient singleton;

	protected MedrxivClient(Optional<Path> optional) {
		super(
				optional, TokenBuckets.builder()
					.withCapacity(2)
					.withInitialTokens(2)
					.withFixedIntervalRefillStrategy(2, 1, TimeUnit.SECONDS)
					.build()
		);
		// api.biorxiv.org takes 3-6 seconds and the JATS fetch much longer; a timeout
		// here looks exactly like "this preprint has no data"
		this.setReadTimeout(30_000);
	}

	public static MedrxivClient create() {
		return create(null);
	}

	public static MedrxivClient create(Path cacheDir) {
		if (singleton == null) {
			singleton = new MedrxivClient(Optional.ofNullable(cacheDir));
		}
		return singleton;
	}

	@Override
	protected MultivaluedMap<String, String> defaultApiParams() {
		return new MultivaluedMapImpl();
	}

	/** whether a DOI belongs to a preprint server, and so is worth a request */
	public static boolean isPreprintDoi(String doi) {
		return doi != null && List.of(PREFIX)
			.stream()
			.anyMatch(
				p -> doi.trim()
					.toLowerCase()
					.startsWith(p)
			);
	}

	/** every version of a preprint, most recent last, or empty if neither server has it */
	public List<MedrxivRecord> versions(String doi) {
		if (!isPreprintDoi(doi)) return List.of();
		for (String server : SERVERS) {
			var response = this.detailsJson(server, doi.trim());
			if (response.isPresent() && response.get()
				.path("collection")
				.isArray()) {
				List<MedrxivRecord> versions = new ArrayList<>();
				for (JsonNode version : response.get()
					.path("collection")) {
					versions.add(new MedrxivRecord(version, server));
				}
				if (!versions.isEmpty()) return versions;
			}
		}
		return List.of();
	}

	private Optional<JsonNode> detailsJson(String server, String doi) {
		return this
			.buildCall(API + "/details/" + server + "/" + doi, JsonNode.class)
			.withParams(this.defaultApiParams())
			.withOperation(is -> this.mapper.readTree(is))
			.get();
	}

	@Override
	public Map<ImmutableRecordReference, ? extends Record> fetch(
			Collection<? extends RecordReference> refs
	) {
		Map<ImmutableRecordReference, Record> out = new HashMap<>();
		for (RecordReference ref : refs) {
			if (!ref.getIdentifierType()
				.equals(IdType.DOI)
					|| !ref.getIdentifier()
						.isPresent()) {
				continue;
			}
			if (!isPreprintDoi(
				ref.getIdentifier()
					.get()
			)) {
				continue; // not ours: no request
			}
			// the latest version describes the preprint as it stands
			var versions = this.versions(
				ref.getIdentifier()
					.get()
			);
			if (versions.isEmpty()) { continue; }
			// keyed by the reference that was asked for, so this merges with other APIs
			out.put(
				Builder.recordReference(ref),
				versions.get(versions.size() - 1)
			);
		}
		return out;
	}

	/**
	 * The references of a preprint, from its JATS full text. Nothing here is fetched for a DOI that is not a preprint, so
	 * this is safe to leave in the default citation expansion.
	 */
	@Override
	public Set<? extends CitationLink> citesReferences(
			Collection<? extends RecordReference> refs
	) {
		Set<CitationLink> out = new LinkedHashSet<>();
		List<String> dois = refs.stream()
			.filter(
				r -> r.getIdentifierType()
					.equals(IdType.DOI)
			)
			.flatMap(
				r -> r.getIdentifier()
					.stream()
			)
			.filter(MedrxivClient::isPreprintDoi)
			.distinct()
			.collect(Collectors.toList());
		for (String doi : dois) {
			this
				.article(
					doi,
					Provenance.canonical(Provenance.MEDRXIV, Optional.empty())
				)
				.ifPresent(entry -> {
					var citations = entry.getCitations();
					log.info(
						"medRxiv JATS gave {} references for {}",
						citations.size(),
						doi
					);
					out.addAll(citations);
				});
		}
		return out;
	}

	/** the preprint's full text as a JATS article, which is where its references live */
	public Optional<JatsEntry> article(String doi, Provenance prov) {
		var versions = this.versions(doi);
		if (versions.isEmpty()) return Optional.empty();
		var jatsUrl = versions.get(versions.size() - 1)
			.getJatsXmlUrl();
		if (!jatsUrl.isPresent()) return Optional.empty();
		return this.fetchJats(jatsUrl.get(), prov);
	}

	/**
	 * The JATS URL redirects (the service moves it to a canonical path), so this goes through HttpURLConnection, which
	 * follows redirects, rather than the shared Jersey client, which does not.
	 */
	private Optional<JatsEntry> fetchJats(String url, Provenance prov) {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) URI.create(url)
				.toURL()
				.openConnection();
			connection.setInstanceFollowRedirects(true);
			connection.setConnectTimeout(10_000);
			connection.setReadTimeout(60_000);
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setRequestProperty("Accept", "application/xml");
			if (connection.getResponseCode() != 200) {
				log.warn(
					"medRxiv JATS returned HTTP {} for {}",
					connection.getResponseCode(),
					url
				);
				return Optional.empty();
			}
			try (var in = connection.getInputStream()) {
				return Optional.of(
					new JatsEntry(
							Xml.fromStream(in)
								.content(),
							prov
					)
				);
			}
		} catch (Exception e) {
			log.warn(
				"could not read the medRxiv JATS at {}: {}",
				url,
				e.getMessage()
			);
			return Optional.empty();
		} finally {
			if (connection != null) { connection.disconnect(); }
		}
	}
	// ---- search: the API lists a date window, it does not search words ----------

	/** used when a caller gives no window: preprints matter most when they are new */

//	@Override
//	public SearchQueryBuilder buildQuery() {
//		return new SearchQueryBuilder(this);
//	}
//
//	public static class SearchQueryBuilder
//			implements SearchBuilder<MedrxivSearch> {
//
//		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
//		List<String> terms = new ArrayList<>();
//		int authors = 0;
//
//		MedrxivClient client;
//
//		public SearchQueryBuilder(MedrxivClient medrxivClient) {
//			this.client = medrxivClient;
//			this.field("format-result", "standard");
//			this.field("jcode", "medrxiv||biorxiv");
//			this.field("sort", "relevance-rank");
//		}
//
//		public SearchQueryBuilder author(String author) {
//			this.field("author" + (this.authors + 1), author);
//			this.authors += 1;
//			return this;
//		}
//
//		private SearchQueryBuilder field(String field, String value) {
//			this.terms.add(field + ":" + this.asValue(value));
//			return this;
//		}
//
//		private Consumer<String> addField(String field) {
//			return s -> this.field(field, s);
//		}
//
//		private String asValue(String value) {
//			return value.replaceAll("\\s+", "+");
//		}
//
//		public SearchQueryBuilder abstractTitle(String term) {
//			this.field("abstract_title", term);
//			this.field("abstract_title_flags", "match-all");
//			return this;
//		}
//
//		public SearchQueryBuilder fullText(String term) {
//			this.field("text_abstract_title", term);
//			this.field("text_abstract_title_flags", "match-all");
//			return this;
//		}
//
//		public SearchQueryBuilder limit(Optional<Integer> l) {
//			l.map(i -> i.toString())
//				.ifPresent(this.addField("numresults"));
//			return this;
//		}
//
//		public SearchQueryBuilder since(Optional<LocalDate> l) {
//			l.map(i -> i.format(this.formatter))
//				.ifPresent(this.addField("limit_from"));
//			return this;
//		}
//
//		public SearchQueryBuilder until(Optional<LocalDate> l) {
//			l.map(i -> i.format(this.formatter))
//				.ifPresent(this.addField("limit_to"));
//			return this;
//		}
//
//		@Override
//		public SearchQueryBuilder withQuery(Query qry) {
//
//			qry.getNativeQuery()
//				.ifPresent(this.terms::add);
//			qry.getTitleAbstract()
//				.ifPresent(this::abstractTitle);
//			qry.getAllFields()
//				.ifPresent(this::fullText);
//			qry.getFullText()
//				.forEach(this::fullText);
//
//			this.limit(qry.getLimit());
//			this.since(qry.getAfter());
//			this.until(qry.getUntil());
//
//			qry.getAuthors()
//				.forEach(this::author);
//			return this;
//		}
//
//		/**
//		 * The search expression this builder would put in the request path, assembled exactly as {@link #execute()} assembles
//		 * it (before URL encoding).
//		 *
//		 * Exposed so the translation from a {@link Query} can be asserted without a network call. It matters more here than
//		 * elsewhere because this is not a documented API but the preprint servers' HTML search interface, where a malformed
//		 * expression returns a plausible-looking page rather than an error.
//		 */
//		public String query() {
//			return this.terms.stream()
//				.collect(Collectors.joining(" "));
//		}
//
//		@Override
//		public Optional<MedrxivSearch> execute() {
//			if (this.terms.size() == 0) return Optional.empty();
//			var qry = this.query();
//
//			var url = SEARCH + UriComponent.encode(qry, Type.PATH);
//
//			return this.client.buildCall(url, MedrxivSearch.class)
//				.withOperation(is -> {
//					var resp = Xml.fromStream(is);
//					return new MedrxivSearch(resp.content());
//				})
//				.get();
//		}
//
//	}
//
//	protected enum SearchFields {
//		AUTHOR(
//				"author"
//		), TITLE_ABSTRACT(
//				"abstract_title"
//		), TEXT(
//				"text_abstract_title"
//		), ALL(
//				null
//		);
//
//		private String marker;
//		SearchFields(String m) {
//			this.marker = m;
//		}
//		public String searchTerm(String value) {
//			if (this.marker == null) return value;
//			return this.marker + ":" + value;
//		}
//	}
//
//	// Need to scrape HMTL. Medrxiv or biorxiv can search both.
//	// https://www.biorxiv.org/search/
// // HOWEVER this can't be done automatically as the cloudflare captcha is triggered
// // A solution using zenapi was tried but that also did not work. 	
//
//	// medrxiv search strings:
//	// long%2Bcovid author1:challen author2:danon jcode:medrxiv limit_from:2026-07-10 numresults:10 sort:relevance-rank
//	// format_result:standard
//	// long%2Bcovid author1:challen author2:danon abstract_title:abstract+or+title+text abstract_title_flags:match-all
//	// text_abstract_title:full+text text_abstract_title_flags:match-all jcode:medrxiv limit_from:2026-07-10
//	// limit_to:2027-02-16 numresults:10 sort:relevance-rank format_result:standard
//	// p53 author1:challen jcode:medrxiv||biorxiv numresults:10 sort:relevance-rank format_result:standard
//
//	/**
//	 * The dates are the API's natural axis: `api.biorxiv.org` lists what was posted in a window, cursor-paged, and has no
//	 * keyword search at all. A term is therefore applied to what comes back rather than sent to the server, which is worth
//	 * knowing when results look narrower than expected — Europe PMC indexes preprints *and* searches words.
//	 */
//	@Override
//	public Collection<Record> search(Query qry) {
//		var tmp = this.buildQuery()
//			.withQuery(qry);
//		return tmp.execute()
//			.stream()
//			.flatMap(MedrxivSearch::stream)
//			.collect(Collectors.toList());
//	}

}
