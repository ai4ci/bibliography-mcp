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
import uk.co.terminological.bibliography.record.Record;
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
public class MedrxivClient extends CachingApiClient implements RecordFetcher, CitesMapper {

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
}
