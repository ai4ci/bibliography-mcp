package uk.co.terminological.bibliography.medrxiv;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDate;
import java.util.Optional;
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
import uk.co.terminological.bibliography.client.IdMapper;
import uk.co.terminological.bibliography.client.Searcher;
import uk.co.terminological.bibliography.client.RecordFetcher;
import uk.co.terminological.bibliography.jats.JatsEntry;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.ImmutableRecordReferenceMapping;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReferenceMapping;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.fluentxml.Xml;

/**
 * medRxiv and bioRxiv, through `api.biorxiv.org`.
 *
 * Two jobs, and the second is the interesting one:
 *
 * - **fetch**: `/details/{server}/{doi}` gives every version of a preprint, with the
 *   server's own metadata (version, license, category, funder, the `published` link).
 * - **cites**: the details response carries a `jatsxml` URL, and the article XML
 *   contains the reference list. That is the only way to get the references of a
 *   preprint that Europe PMC does not index — which it often does not: coverage is
 *   partial. For preprints Europe PMC *does* index, it is the better source, and the
 *   merge priority says so.
 *
 * Only DOIs under the bioRxiv/medRxiv prefix `10.1101/` are looked at, so adding this
 * client to the default citation expansion costs nothing for ordinary journal DOIs.
 *
 * [IMPLEMENTS](/design/features/feat-009-preprint-clients.md)
 */
public class MedrxivClient extends CachingApiClient implements Searcher, RecordFetcher, CitesMapper, IdMapper {

	static Logger log = LoggerFactory.getLogger(MedrxivClient.class);

	private static final String API = "https://api.biorxiv.org";
	private static final String PREFIX = "10.1101/";
	private static final List<String> SERVERS = List.of("medrxiv", "biorxiv");
	private static final String USER_AGENT = "bibliographic-api-client/1.0";

	private final ObjectMapper mapper = new ObjectMapper();
	private static MedrxivClient singleton;

	protected MedrxivClient(Optional<Path> optional) {
		super(optional, TokenBuckets.builder()
				.withCapacity(2)
				.withInitialTokens(2)
				.withFixedIntervalRefillStrategy(2, 1, TimeUnit.SECONDS).build());
		// api.biorxiv.org takes 3-6 seconds: the shared 3s read timeout turns every
		// call into a timeout, which looks exactly like "this preprint has no data"
		this.setReadTimeout(30_000);
	}

	public static MedrxivClient create() {
		return create(null);
	}

	public static MedrxivClient create(Path cacheDir) {
		if (singleton == null) singleton = new MedrxivClient(Optional.ofNullable(cacheDir));
		return singleton;
	}

	@Override
	protected MultivaluedMap<String, String> defaultApiParams() {
		return new MultivaluedMapImpl();
	}

	/** whether a DOI belongs to a preprint server, and so is worth a request */
	public static boolean isPreprintDoi(String doi) {
		return doi != null && doi.trim().toLowerCase().startsWith(PREFIX);
	}

	/** every version of a preprint, most recent last, or empty if neither server has it */
	public List<MedrxivRecord> versions(String doi) {
		if (!isPreprintDoi(doi)) return List.of();
		for (String server : SERVERS) {
			Optional<JsonNode> response = detailsJson(server, doi.trim());
			if (response.isPresent() && response.get().path("collection").isArray()) {
				List<MedrxivRecord> versions = new ArrayList<>();
				for (JsonNode version : response.get().path("collection")) {
					versions.add(new MedrxivRecord(version, server));
				}
				if (!versions.isEmpty()) return versions;
			}
		}
		return List.of();
	}

	private Optional<JsonNode> detailsJson(String server, String doi) {
		return this.buildCall(API + "/details/" + server + "/" + doi, JsonNode.class)
				.withParams(defaultApiParams())
				.withOperation(is -> mapper.readTree(is))
				.get();
	}

	@Override
	public Map<ImmutableRecordReference, ? extends Record> fetch(Collection<? extends RecordReference> refs) {
		Map<ImmutableRecordReference, Record> out = new HashMap<>();
		for (RecordReference ref : refs) {
			if (!ref.getIdentifierType().equals(IdType.DOI) || !ref.getIdentifier().isPresent()) continue;
			if (!isPreprintDoi(ref.getIdentifier().get())) continue; // not ours: no request
			// the latest version describes the preprint as it stands
			List<MedrxivRecord> versions = versions(ref.getIdentifier().get());
			if (versions.isEmpty()) continue;
			// keyed by the reference that was asked for, so this merges with other APIs
			out.put(Builder.recordReference(ref), versions.get(versions.size() - 1));
		}
		return out;
	}

	/**
	 * The references of a preprint, from its JATS full text. Nothing here is fetched
	 * for a DOI that is not a preprint, so this is safe to leave in the default
	 * citation expansion.
	 */
	@Override
	public Set<? extends CitationLink> citesReferences(Collection<? extends RecordReference> refs) {
		Set<CitationLink> out = new LinkedHashSet<>();
		List<String> dois = refs.stream()
				.filter(r -> r.getIdentifierType().equals(IdType.DOI))
				.flatMap(r -> r.getIdentifier().stream())
				.filter(MedrxivClient::isPreprintDoi)
				.distinct()
				.collect(Collectors.toList());
		for (String doi : dois) {
			article(doi).ifPresent(entry -> {
				List<CitationLink> citations = entry.getCitations().collect(Collectors.toList());
				log.info("medRxiv JATS gave {} references for {}", citations.size(), doi);
				out.addAll(citations);
			});
		}
		return out;
	}

	/** the preprint's full text as a JATS article, which is where its references live */
	public Optional<JatsEntry> article(String doi) {
		List<MedrxivRecord> versions = versions(doi);
		if (versions.isEmpty()) return Optional.empty();
		Optional<String> jatsUrl = versions.get(versions.size() - 1).getJatsXmlUrl();
		if (!jatsUrl.isPresent()) return Optional.empty();
		return fetchJats(jatsUrl.get());
	}

	/**
	 * The JATS URL redirects (the service moves it to a canonical path), so this goes
	 * through HttpURLConnection, which follows redirects, rather than the shared
	 * Jersey client, which does not.
	 */
	private Optional<JatsEntry> fetchJats(String url) {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setInstanceFollowRedirects(true);
			connection.setConnectTimeout(10_000);
			connection.setReadTimeout(60_000);
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setRequestProperty("Accept", "application/xml");
			if (connection.getResponseCode() != 200) {
				log.warn("medRxiv JATS returned HTTP {} for {}", connection.getResponseCode(), url);
				return Optional.empty();
			}
			try (InputStream in = connection.getInputStream()) {
				return Optional.of(new JatsEntry(Xml.fromStream(in).content()));
			}
		} catch (Exception e) {
			log.warn("could not read the medRxiv JATS at {}: {}", url, e.getMessage());
			return Optional.empty();
		} finally {
			if (connection != null) connection.disconnect();
		}
	}
	// ---- search: the API lists a date window, it does not search words ----------

	/** used when a caller gives no window: preprints matter most when they are new */
	public static final int DEFAULT_WINDOW_DAYS = 30;
	private static final int MAX_PAGES = 10;

	/**
	 * The dates are the API's natural axis: `api.biorxiv.org` lists what was posted in
	 * a window, cursor-paged, and has no keyword search at all. A term is therefore
	 * applied to what comes back rather than sent to the server, which is worth knowing
	 * when results look narrower than expected — Europe PMC indexes preprints *and*
	 * searches words.
	 */
	@Override
	public Collection<? extends Record> search(String term, Optional<LocalDate> from,
			Optional<LocalDate> to, Optional<Integer> limit) {
		LocalDate end = to.orElse(LocalDate.now());
		LocalDate start = from.orElse(end.minusDays(DEFAULT_WINDOW_DAYS));
		int wanted = limit.orElse(50);
		List<Record> out = new ArrayList<>();
		for (String server : SERVERS) {
			out.addAll(window(server, start, end, term, wanted - out.size()));
			if (out.size() >= wanted) break;
		}
		log.info("medRxiv window {}..{} for '{}' gave {} records", start, end, term, out.size());
		return out;
	}

	private List<Record> window(String server, LocalDate from, LocalDate to, String term, int wanted) {
		List<Record> out = new ArrayList<>();
		int cursor = 0;
		int windowTotal = Integer.MAX_VALUE;
		for (int page = 0; page < MAX_PAGES && out.size() < wanted; page++) {
			Optional<JsonNode> response = windowJson(server, from, to, cursor);
			if (!response.isPresent()) break;
			JsonNode collection = response.get().path("collection");
			if (!collection.isArray() || collection.size() == 0) break;
			for (JsonNode version : collection) {
				MedrxivRecord record = new MedrxivRecord(version, server);
				if (matches(record, term)) out.add(record);
			}
			cursor += collection.size();
			windowTotal = response.get().path("messages").path(0).path("total").asInt(cursor);
		}
		if (cursor < windowTotal) {
			// a month can hold thousands of preprints and the API pages thirty at a
			// time, so a search of a busy window is partial by construction: say so
			log.info("medRxiv {}: scanned {} of {} records in the window; narrow the dates for fuller coverage",
					server, cursor, windowTotal);
		}
		return out.size() > wanted ? out.subList(0, wanted) : out;
	}

	private Optional<JsonNode> windowJson(String server, LocalDate from, LocalDate to, int cursor) {
		return this.buildCall(API + "/details/" + server + "/" + from + "/" + to + "/" + cursor, JsonNode.class)
				.withParams(defaultApiParams())
				.withOperation(is -> mapper.readTree(is))
				.get();
	}

	/**
	 * Whether a record matches the term. Every word must appear somewhere in the title
	 * or the abstract: a whole phrase as one substring almost never matches, which made
	 * a two-word query return nothing at all in a window holding 1498 preprints.
	 */
	static boolean matches(MedrxivRecord record, String term) {
		if (term == null || term.trim().isEmpty()) return true;
		String haystack = (record.getTitle().orElse("") + " " + record.getAbstract().orElse(""))
				.toLowerCase(java.util.Locale.ROOT);
		for (String word : term.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
			if (word.length() < 2) continue;
			if (!haystack.contains(word)) return false;
		}
		return true;
	}

	// ---- mapping: a preprint and its published version --------------------------

	/**
	 * `api.biorxiv.org` records which journal article a preprint became. Exposed as an
	 * id mapping, which means a session holding both will treat them as one work — the
	 * right answer for a citation graph (one paper, cited once) and a deliberate choice
	 * rather than a side effect.
	 */
	@Override
	public Set<? extends RecordReferenceMapping> mappings(Collection<? extends RecordReference> refs) {
		Set<RecordReferenceMapping> out = new LinkedHashSet<>();
		for (RecordReference ref : refs) {
			if (!ref.getIdentifierType().equals(IdType.DOI) || !ref.getIdentifier().isPresent()) continue;
			if (!isPreprintDoi(ref.getIdentifier().get())) continue; // not ours: no request
			publishedAs(ref.getIdentifier().get()).ifPresent(out::add);
		}
		return out;
	}

	/** the published-version mapping for one preprint, from the `/pubs` endpoint */
	public Optional<RecordReferenceMapping> publishedAs(String preprintDoi) {
		if (!isPreprintDoi(preprintDoi)) return Optional.empty();
		for (String server : SERVERS) {
			Optional<JsonNode> response = this
					.buildCall(API + "/pubs/" + server + "/" + preprintDoi.trim(), JsonNode.class)
					.withParams(defaultApiParams())
					.withOperation(is -> mapper.readTree(is))
					.get();
			Optional<RecordReferenceMapping> mapping = response.flatMap(json -> mappingFromPubs(json, preprintDoi));
			if (mapping.isPresent()) return mapping;
		}
		return Optional.empty();
	}

	/** the preprint-to-published pair in a `/pubs` response, if it holds one */
	static Optional<RecordReferenceMapping> mappingFromPubs(JsonNode response, String preprintDoi) {
		JsonNode collection = response.path("collection");
		if (!collection.isArray() || collection.size() == 0) return Optional.empty();
		JsonNode first = collection.get(0);
		String published = first.path("published_doi").asText(null);
		String preprint = first.hasNonNull("preprint_doi") ? first.get("preprint_doi").asText() : preprintDoi;
		if (published == null || published.trim().isEmpty() || preprint == null) return Optional.empty();
		return Optional.of(new ImmutableRecordReferenceMapping(
				Builder.recordReference(IdType.DOI, preprint),
				Builder.recordReference(IdType.DOI, published.trim())));
	}
}
