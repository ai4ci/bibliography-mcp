package uk.co.terminological.bibliography;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.isomorphism.util.TokenBuckets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandler;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import pl.edu.icm.cermine.ContentExtractor;
import pl.edu.icm.cermine.bibref.model.BibEntry;
import pl.edu.icm.cermine.bibref.model.BibEntryType;

public class PdfFetcher extends CachingApiClient {

	static Logger logger = LoggerFactory.getLogger(PdfFetcher.class);

	static PdfFetcher instance = null;

	int maxRedirects = 10;
	Path cache = null;

	private PdfFetcher(Optional<Path> cachePath) {
		super(
				cachePath, TokenBuckets.builder()
					.withInitialTokens(10)
					.withCapacity(10)
					.withFixedIntervalRefillStrategy(10, 1, TimeUnit.SECONDS)
					.build()
		);
		ClientConfig config = new DefaultClientConfig();
		config.getProperties()
			.put(ClientConfig.PROPERTY_FOLLOW_REDIRECTS, true);
		client = Client.create(config);
		client.setFollowRedirects(true);
		client.setConnectTimeout(1000);
		client.setReadTimeout(4000);
		client.addFilter(new ClientFilter() {
			@Override
			public ClientResponse handle(ClientRequest request)
					throws ClientHandlerException {
				request.getHeaders()
					.add(
						HttpHeaders.USER_AGENT,
						"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/64.0.3282.119 Safari/537.36"
					);
				request.getHeaders()
					.add(HttpHeaders.ACCEPT, "application/pdf");
				return getNext().handle(request);
			}
		});
		client.addFilter(new ClientFilter() {
			@Override
			public ClientResponse handle(ClientRequest cr)
					throws ClientHandlerException {
				ClientHandler ch = getNext();
				int i = maxRedirects;
				while (i-- > 0) {
					ClientResponse resp = ch.handle(cr);
					if (resp.getClientResponseStatus()
						.getFamily() != Response.Status.Family.REDIRECTION) {
						return resp;
					} else {
						// try location
						String redirectTarget = resp.getHeaders()
							.getFirst("Location");
						logger.debug("redirecting to :" + redirectTarget);
						cr.setURI(
							UriBuilder.fromUri(redirectTarget)
								.build()
						);
						resp = ch.handle(cr);
					}
				}
				throw new ClientHandlerException("Too many redirects");
			}
		});
	}

	public static PdfFetcher create() {
		return new PdfFetcher(Optional.empty());
	}

	public static PdfFetcher create(Path cachePath) {
		if (instance == null)
			instance = new PdfFetcher(Optional.ofNullable(cachePath));
		return instance;
	}

	public PdfFetcher maxRedirects(int redirects) {
		this.maxRedirects = redirects;
		return this;
	}

	public Optional<InputStream> getPdfFromUrl(String url) {
		return this.buildCall(url, InputStream.class)
			.cacheForever()
			.withOperation(is -> is)
			.get();
	}

	/**
	 * Download a pdf and write it to a file.
	 *
	 * The stream form above hands back a view of a cache blob, which no other process can read: Zotero cannot attach
	 * it and nothing can index it. This is the same download, landing somewhere a file actually is.
	 *
	 * A body that is empty or is not a pdf — a paywall page answering an open-access link, usually — is refused by
	 * {@link PdfSink} and leaves nothing behind, so a failure here never produces an attachment that looks fine until
	 * someone opens it.
	 *
	 * @return the file written, or empty when the url gave nothing at all
	 * @throws java.io.IOException when something arrived and it was not a pdf
	 */
	public Optional<java.nio.file.Path> fetchTo(
			String url, java.nio.file.Path destination
	) throws java.io.IOException {
		var stream = this.getPdfFromUrl(url);
		if (stream.isEmpty()) return Optional.empty();
		try (var in = stream.get()) {
			return Optional.of(PdfSink.write(in, destination));
		}
	}

	@Override
	protected MultivaluedMap<String, String> defaultApiParams() {
		return new MultivaluedMapImpl();
	}

	public List<String> extractArticleRefs(String doi, InputStream is) {
		String key = "cermine_refs_" + doi;
		Optional<ArrayList<String>> references = this
			.cachedObject(key, false, k -> {
				ContentExtractor extractor = new ContentExtractor();
				extractor.setPDF(is);
				List<BibEntry> refs = extractor.getReferences();
				logger.info("Found {} references for {}", refs.size(), key);
				ArrayList<String> out = new ArrayList<>();
				refs.stream()
					.filter(
						ref -> Arrays
							.asList(
								BibEntryType.ARTICLE,
								BibEntryType.INPROCEEDINGS,
								BibEntryType.PROCEEDINGS
							)
							.contains(ref.getType())
					)
					.map(bib -> bib.getText())
					.forEach(s -> out.add(s));
				return out;
			});
		return references.orElse(new ArrayList<>());
		// TODO: get mapped metadata
	}

	// TODO: Stream<Citation> getCitations(Record sourceWithDoi) {
	// USing BibEntryFieldType / BibEntry

}
