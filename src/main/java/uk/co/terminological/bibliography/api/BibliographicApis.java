package uk.co.terminological.bibliography.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.terminological.bibliography.PdfFetcher;
import uk.co.terminological.bibliography.api.crossref.CrossRefClient;
import uk.co.terminological.bibliography.api.entrez.EntrezClient;
import uk.co.terminological.bibliography.api.europepmc.EuropePMCClient;
import uk.co.terminological.bibliography.api.medrxiv.MedrxivClient;
import uk.co.terminological.bibliography.api.opencitations.OpenCitationsClient;
import uk.co.terminological.bibliography.api.openalex.OpenAlexClient;
import uk.co.terminological.bibliography.api.pmcidconv.PMCIDClient;

public class BibliographicApis {

	// https://elibrary.exeter.ac.uk/idp/shibboleth
	// https://ssologin.exeter.ac.uk/distauth/UI/Login?realm=%2Fpeople
	// TODO: don't think this will really work as shibbolteh login requires going to login links on different publishers
	// sites.

	// TODO: integrate CSL:
	// https://michel-kraemer.github.io/citeproc-java/api/1.0.1/de/undercouch/citeproc/csl/CSLItemDataBuilder.html
	// TODO: Consider a google scholar scraper
	// TODO https://www.crossref.org/labs/citation-formatting-service/
	// TODO http://api.altmetric.com/
	// TODO PDFBox or https://github.com/CeON/CERMINE
	// TODO https://api.altmetric.com/v1/doi/10.1038/480426a

	// TODO https://github.com/joelittlejohn/jsonschema2pojo/wiki/Getting-Started#the-maven-plugin

	// TODO https://github.com/ncbi/JATSPreviewStylesheets
	// TODO https://github.com/PeerJ/jats-conversion/blob/master/src/data/xsl/jats-to-html.xsl
	private static final Logger logger = LoggerFactory
		.getLogger(BibliographicApis.class);

	public static BibliographicApis create(Path filePath) throws IOException {

		Properties prop = read(filePath);

		String xrefToken = prop.getProperty("crossref-clickthroughtoken");
		String developerEmail = prop.getProperty("developer-email");
		String pubmedApiToken = prop.getProperty("pubmed-apikey");
		String openAlexApiKey = prop.getProperty("openalex-apikey");
		String appId = prop.getProperty("app-id");

		String cacheDir = prop.getProperty("cache-directory");
		if (cacheDir != null) {
			return budgeted(
				create(
					appId,
					developerEmail,
					xrefToken,
					pubmedApiToken,
					openAlexApiKey,
					Paths.get(cacheDir.replace("~", System.getProperty("user.home")))
				), prop
			);
		} else {
			return budgeted(
				create(
					appId, developerEmail, xrefToken, pubmedApiToken, openAlexApiKey
				), prop
			);
		}

	}

	/**
	 * Read the configuration into a table of its own.
	 *
	 * Not {@code System.getProperties()}, which is the live system property table rather than a copy: loading into it
	 * made every API key a JVM-wide property, readable by any library in the process and printed by anything that
	 * dumps system properties into a log, and let one configuration's values bleed into the next (issue-021).
	 *
	 * The stream is closed, which the version this replaced also did not do.
	 */
	private static Properties read(Path filePath) throws IOException {
		Properties properties = new Properties();
		try (var in = Files.newInputStream(filePath)) {
			properties.load(in);
		}
		return properties;
	}

	/**
	 * Apply the OpenAlex credit budget from the configuration.
	 *
	 * Only OpenAlex meters by credit, and the ceiling is a property rather than something the API will tell us before it
	 * is reached. The reserve is what keeps searching — 10 credits a call — from spending the allowance that the fetch
	 * and citation roles, which no other client fills, need at 1 credit a call.
	 */
	private static BibliographicApis budgeted(
			BibliographicApis apis, Properties prop
	) {
		apis.getOpenAlex()
			.withBudget(
				longProperty(
					prop, "openalex-daily-credits",
					OpenAlexClient.DEFAULT_DAILY_CREDITS
				),
				longProperty(
					prop, "openalex-search-credit-reserve",
					OpenAlexClient.DEFAULT_SEARCH_CREDIT_RESERVE
				)
			);
		return apis;
	}

	/** a configured number, or the default when it is absent or not a number — a typo should not disable the budget */
	private static long longProperty(
			Properties prop, String key, long fallback
	) {
		String value = prop.getProperty(key);
		if (value == null) return fallback;
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			logger.warn(
				"{} is not a number ({}); using {}", key, value, fallback
			);
			return fallback;
		}
	}

	public static BibliographicApis create(Path filePath, Path cacheDir)
			throws IOException {
		Properties prop = read(filePath);

		String xrefToken = prop.getProperty("crossref-clickthroughtoken");
		String developerEmail = prop.getProperty("developer-email");
		String pubmedApiToken = prop.getProperty("pubmed-apikey");
		String openAlexApiKey = prop.getProperty("openalex-apikey");
		String appId = prop.getProperty("app-id");

		return budgeted(
			create(
				appId, developerEmail, xrefToken, pubmedApiToken, openAlexApiKey,
				cacheDir
			), prop
		);

	}

	public static BibliographicApis create(
			String appId, String developerEmail, String xrefToken,
			String pubmedApiToken
	) {
		return new BibliographicApis(
				appId, developerEmail, xrefToken, pubmedApiToken, null,
				Optional.empty()
		);
	}

	public static BibliographicApis create(
			String appId, String developerEmail, String xrefToken,
			String pubmedApiToken, Path cacheDir
	) {
		return new BibliographicApis(
				appId, developerEmail, xrefToken, pubmedApiToken, null,
				Optional.ofNullable(cacheDir)
		);
	}

	/**
	 * @param openAlexApiKey the OpenAlex token, or null to use the free tier. OpenAlex works without a key, so unlike the
	 * other credentials this one being absent is ordinary rather than a misconfiguration — it just means a smaller budget.
	 */
	public static BibliographicApis create(
			String appId, String developerEmail, String xrefToken,
			String pubmedApiToken, String openAlexApiKey
	) {
		return new BibliographicApis(
				appId, developerEmail, xrefToken, pubmedApiToken, openAlexApiKey,
				Optional.empty()
		);
	}

	public static BibliographicApis create(
			String appId, String developerEmail, String xrefToken,
			String pubmedApiToken, String openAlexApiKey, Path cacheDir
	) {
		return new BibliographicApis(
				appId, developerEmail, xrefToken, pubmedApiToken, openAlexApiKey,
				Optional.ofNullable(cacheDir)
		);
	}

	private EntrezClient entrez;
	private PMCIDClient pmcIdConv;
	private CrossRefClient crossref;
	private PdfFetcher pdfFetcher;
	private EuropePMCClient europepmc;
	private OpenCitationsClient opencitations;
	private MedrxivClient medrxiv;
	private OpenAlexClient openalex;

	private BibliographicApis(
			String appId, String developerEmail, String xrefToken,
			String pubmedApiToken, String openAlexApiKey, Optional<Path> cacheDir
	) {

		logger.info(
			"Setting up API client with cache: " + cacheDir.map(Path::toString)
				.orElse("memory")
		);
		entrez = EntrezClient.create(
			pubmedApiToken,
			appId,
			developerEmail,
			cacheDir.map(o -> o.resolve("entrez"))
				.orElse(null)
		);
		pmcIdConv = PMCIDClient.create(
			appId,
			developerEmail,
			cacheDir.map(o -> o.resolve("idconv"))
				.orElse(null)
		);
		crossref = CrossRefClient.create(
			developerEmail,
			cacheDir.map(o -> o.resolve("xref"))
				.orElse(null)
		);
		pdfFetcher = PdfFetcher.create(
			cacheDir.map(o -> o.resolve("pdf"))
				.orElse(null)
		);
		europepmc = EuropePMCClient.create(
			developerEmail,
			cacheDir.map(o -> o.resolve("europepmc"))
				.orElse(null)
		);
		opencitations = OpenCitationsClient.create(
			cacheDir.map(o -> o.resolve("opencitations"))
				.orElse(null)
		);
		medrxiv = MedrxivClient.create(
			cacheDir.map(o -> o.resolve("medrxiv"))
				.orElse(null)
		);
		openalex = OpenAlexClient.create(
			openAlexApiKey,
			developerEmail,
			cacheDir.map(o -> o.resolve("openalex"))
				.orElse(null)
		);
	}

	public BibliographicApis debugMode() {
		entrez.debugMode();
		pmcIdConv.debugMode();
		crossref.debugMode();
		europepmc.debugMode();
		opencitations.debugMode();
		medrxiv.debugMode();
		openalex.debugMode();
		return this;
	}

	public BibliographicApis shutdown() {
		entrez.shutdown();
		pmcIdConv.shutdown();
		crossref.shutdown();
		europepmc.shutdown();
		opencitations.shutdown();
		medrxiv.shutdown();
		openalex.shutdown();
		return this;
	}

	public EntrezClient getEntrez() { return entrez; }

	public PMCIDClient getPmcIdConv() { return pmcIdConv; }

	public CrossRefClient getCrossref() { return crossref; }

	public PdfFetcher getPdfFetcher() { return pdfFetcher; }

	public EuropePMCClient getEuropePMC() { return europepmc; }

	public MedrxivClient getMedrxiv() { return medrxiv; }

	public OpenCitationsClient getOpenCitationsClient() { return opencitations; }

	/** OpenAlex: the citation graph, the identifier crosswalk and the open-access locator. */
	public OpenAlexClient getOpenAlex() { return openalex; }

	public void disableCache() {
		this.entrez.disableCache();
		this.crossref.disableCache();
		this.europepmc.disableCache();
		this.opencitations.disableCache();
		this.pdfFetcher.disableCache();
		this.pmcIdConv.disableCache();
		this.openalex.disableCache();
	}

	public void enableCache() {
		this.entrez.enableCache();
		this.crossref.enableCache();
		this.europepmc.enableCache();
		this.opencitations.enableCache();
		this.pdfFetcher.enableCache();
		this.pmcIdConv.enableCache();
		this.openalex.enableCache();
	}

}
