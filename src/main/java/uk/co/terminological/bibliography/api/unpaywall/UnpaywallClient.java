package uk.co.terminological.bibliography.api.unpaywall;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.ws.rs.core.MultivaluedMap;

import org.isomorphism.util.TokenBuckets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import uk.co.terminological.bibliography.CachingApiClient;
import uk.co.terminological.bibliography.PdfFetcher;
import uk.co.terminological.bibliography.api.BibliographicApiException;
import uk.co.terminological.bibliography.client.RecordFetcher;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.RecordReference;

public class UnpaywallClient extends CachingApiClient implements RecordFetcher {

	// api.unpaywall.org/v2/DOI?email=YOUR_EMAIL.
	// https://unpaywall.org/YOUR_DOI

	private static final Logger logger = LoggerFactory
		.getLogger(UnpaywallClient.class);

	private String developerEmail;
	private ObjectMapper objectMapper = new ObjectMapper();
	private PdfFetcher pdfFetcher = null;

	private PdfFetcher getPdfFetcher() {
		if (pdfFetcher == null)
			pdfFetcher = PdfFetcher.create(cache.resolve("pdf"));
		return pdfFetcher;
	}

	private Path cache = null;
	private static HashMap<String, UnpaywallClient> singleton = new HashMap<>();

	public static UnpaywallClient create(String developerEmail) {
		return create(developerEmail, null);
	}

	public UnpaywallClient withPdfFetcher(PdfFetcher fetcher) {
		this.pdfFetcher = fetcher;
		return this;
	}

	public static UnpaywallClient create(String developerEmail, Path cachePath) {
		if (!singleton.containsKey(developerEmail)) {
			UnpaywallClient tmp = new UnpaywallClient(
					developerEmail, Optional.ofNullable(cachePath)
			);
			singleton.put(developerEmail, tmp);
		}
		return singleton.get(developerEmail);
	}

	public UnpaywallClient withCache(Path cache) {
		this.cache = cache;
		return this;
	}

	private UnpaywallClient(String developerEmail, Optional<Path> cachePath) {
		super(
				cachePath, TokenBuckets.builder()
					.withInitialTokens(1000)
					.withCapacity(1000)
					.withFixedIntervalRefillStrategy(
						1000,
						24 * 6 * 6,
						TimeUnit.SECONDS
					)
					.build()
		);
		this.developerEmail = developerEmail;

	}

	protected MultivaluedMap<String, String> defaultApiParams() {
		MultivaluedMap<String, String> out = new MultivaluedMapImpl();
		out.add("email", developerEmail);
		return out;
	}

	public Optional<UnpaywallResult> getUnpaywallByDoi(String doi) {
		logger.debug("fetching unpaywall record for: {}", doi);
		return this
			.buildCall(
				"https://api.unpaywall.org/v2/" + encode(doi),
				UnpaywallResult.class
			)
			.cacheForever()
			.withOperation(is -> new UnpaywallResult(objectMapper.readTree(is)))
			.get();
	}

	public Set<UnpaywallResult> getUnpaywallByDois(Collection<String> dois) {
		return dois.stream()
			.flatMap(doi -> getUnpaywallByDoi(doi).stream())
			.collect(Collectors.toSet());
	}

	public Optional<InputStream> getPdfByResult(UnpaywallResult result) {
		try {
			String url = result.getPdfUri()
				.orElseThrow(
					() -> new BibliographicApiException(
							"no pdf for doi: " + result.getIdentifier()
					)
				)
				.toString();
			return getPdfFetcher().getPdfFromUrl(url);
		} catch (Exception e) {
			logger.debug(
				"Cannot fetch content for {} - {}",
				result.getIdentifier()
					.orElse("?"),
				e.getLocalizedMessage()
			);
			return Optional.empty();
		}
	}

	public Optional<InputStream> getPdfByDoi(String doi) {
		Optional<UnpaywallResult> result = getUnpaywallByDoi(doi);
		return result.flatMap(r -> getPdfByResult(r));
	}

	@Override
	public Map<ImmutableRecordReference, ? extends Record> fetch(
			Collection<? extends RecordReference> equivalentIds
	) {
		Map<ImmutableRecordReference, UnpaywallResult> out = new HashMap<>();
		Collection<String> dois = equivalentIds.stream()
			.filter(
				i -> i.getIdentifierType()
					.equals(IdType.DOI)
			)
			.flatMap(
				i -> i.getIdentifier()
					.stream()
			)
			.collect(Collectors.toList());
		getUnpaywallByDois(dois).forEach(
			upw -> Builder.recordReference(upw)
				.ifComplete(k -> out.put(k, upw))
		);
		return out;
	}

}
