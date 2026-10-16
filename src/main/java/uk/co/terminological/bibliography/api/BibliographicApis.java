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
import uk.co.terminological.bibliography.api.pmcidconv.PMCIDClient;
import uk.co.terminological.bibliography.api.unpaywall.UnpaywallClient;

public class BibliographicApis {

	// https://elibrary.exeter.ac.uk/idp/shibboleth
	// https://ssologin.exeter.ac.uk/distauth/UI/Login?realm=%2Fpeople
	// TODO: don't think this will really work as shibbolteh login requires going to login links on different publishers
	// sites.

	// TODO: integrate CSL:
	// https://michel-kraemer.github.io/citeproc-java/api/1.0.1/de/undercouch/citeproc/csl/CSLItemDataBuilder.html
	// TODO: Consider a google scholar scraper
	// TODO: https://www.crossref.org/labs/citation-formatting-service/
	// TODO: http://api.altmetric.com/
	// TODO: PDFBox or https://github.com/CeON/CERMINE
	// TODO: https://api.altmetric.com/v1/doi/10.1038/480426a

	// TODO: https://github.com/joelittlejohn/jsonschema2pojo/wiki/Getting-Started#the-maven-plugin

	// TODO: https://github.com/ncbi/JATSPreviewStylesheets
	// TODO: https://github.com/PeerJ/jats-conversion/blob/master/src/data/xsl/jats-to-html.xsl
	private static final Logger logger = LoggerFactory
		.getLogger(BibliographicApis.class);

	public static BibliographicApis create(Path filePath) throws IOException {

		Properties prop = System.getProperties();
		prop.load(Files.newInputStream(filePath));

		String xrefToken = prop.getProperty("crossref-clickthroughtoken");
		String developerEmail = prop.getProperty("developer-email");
		String pubmedApiToken = prop.getProperty("pubmed-apikey");
		String appId = prop.getProperty("app-id");

		String cacheDir = prop.getProperty("cache-directory");
		if (cacheDir != null) {
			return create(
				appId,
				developerEmail,
				xrefToken,
				pubmedApiToken,
				Paths.get(cacheDir.replace("~", System.getProperty("user.home")))
			);
		} else {
			return create(appId, developerEmail, xrefToken, pubmedApiToken);
		}

	}

	public static BibliographicApis create(Path filePath, Path cacheDir)
			throws IOException {
		Properties prop = System.getProperties();
		prop.load(Files.newInputStream(filePath));

		String xrefToken = prop.getProperty("crossref-clickthroughtoken");
		String developerEmail = prop.getProperty("developer-email");
		String pubmedApiToken = prop.getProperty("pubmed-apikey");
		String appId = prop.getProperty("app-id");

		return create(appId, developerEmail, xrefToken, pubmedApiToken, cacheDir);

	}

	public static BibliographicApis create(
			String appId, String developerEmail, String xrefToken,
			String pubmedApiToken
	) {
		return new BibliographicApis(
				appId, developerEmail, xrefToken, pubmedApiToken, Optional.empty()
		);
	}

	public static BibliographicApis create(
			String appId, String developerEmail, String xrefToken,
			String pubmedApiToken, Path cacheDir
	) {
		return new BibliographicApis(
				appId, developerEmail, xrefToken, pubmedApiToken,
				Optional.ofNullable(cacheDir)
		);
	}

	private EntrezClient entrez;
	private PMCIDClient pmcIdConv;
	private CrossRefClient crossref;
	private UnpaywallClient unpaywall;
	private PdfFetcher pdfFetcher;
	private EuropePMCClient europepmc;
	private OpenCitationsClient opencitations;
	private MedrxivClient medrxiv;

	private BibliographicApis(
			String appId, String developerEmail, String xrefToken,
			String pubmedApiToken, Optional<Path> cacheDir
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
		unpaywall = UnpaywallClient.create(
			developerEmail,
			cacheDir.map(o -> o.resolve("unpaywall"))
				.orElse(null)
		);
		pdfFetcher = PdfFetcher.create(
			cacheDir.map(o -> o.resolve("pdf"))
				.orElse(null)
		);
		unpaywall.withPdfFetcher(pdfFetcher);
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
	}

	public BibliographicApis debugMode() {
		entrez.debugMode();
		pmcIdConv.debugMode();
		crossref.debugMode();
		unpaywall.debugMode();
		europepmc.debugMode();
		opencitations.debugMode();
		medrxiv.debugMode();
		return this;
	}

	public BibliographicApis shutdown() {
		entrez.shutdown();
		pmcIdConv.shutdown();
		crossref.shutdown();
		unpaywall.shutdown();
		europepmc.shutdown();
		opencitations.shutdown();
		medrxiv.shutdown();
		return this;
	}

	public EntrezClient getEntrez() { return entrez; }

	public PMCIDClient getPmcIdConv() { return pmcIdConv; }

	public CrossRefClient getCrossref() { return crossref; }

	public UnpaywallClient getUnpaywall() { return unpaywall; }

	public PdfFetcher getPdfFetcher() { return pdfFetcher; }

	public EuropePMCClient getEuropePMC() { return europepmc; }

	public MedrxivClient getMedrxiv() { return medrxiv; }

	public OpenCitationsClient getOpenCitationsClient() { return opencitations; }

	public void disableCache() {
		this.entrez.disableCache();
		this.crossref.disableCache();
		this.europepmc.disableCache();
		this.opencitations.disableCache();
		this.pdfFetcher.disableCache();
		this.pmcIdConv.disableCache();
		this.unpaywall.disableCache();
	}

	public void enableCache() {
		this.entrez.enableCache();
		this.crossref.enableCache();
		this.europepmc.enableCache();
		this.opencitations.enableCache();
		this.pdfFetcher.enableCache();
		this.pmcIdConv.enableCache();
		this.unpaywall.enableCache();
	}

}
