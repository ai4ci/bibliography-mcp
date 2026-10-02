package uk.co.terminological.bibliography;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Properties;

/**
 * Tier-1 fixture capture tool (plan-007-api-test-coverage). Hits the exact endpoints
 * the clients build for the canonical corpus and writes the raw response bytes to
 * {@code src/test/resources/fixtures/<api>/<name>}. Dependency-free (JDK only) so it
 * runs directly:
 *
 *   java src/test/java/uk/co/terminological/bibliography/FixtureRecorder.java
 *
 * Git history is the fixture changelog; re-run to refresh the corpus when an API
 * changes. An optional third argument filters capture to fixture names starting with
 * that prefix, e.g. {@code ... src/test/resources/fixtures entrez/}. Entrez endpoints
 * are captured over GET: the client POSTs the same parameters as a form, but eutils
 * answers identically and Tier-1 parsing only ever sees the response bytes.
 */
public class FixtureRecorder {

	// canonical corpus (see TestPaperFixtures)
	static final String PUB_DOI   = "10.1186/s12879-026-13122-y"; // PMID 41888721, PMC13141338
	static final String PREP_DOI  = "10.64898/2025.12.12.25342129"; // PPR1270844
	static final String CITED_DOI = "10.1101/2021.02.09.21250937";  // citation-rich medRxiv
	static final String CITED_PPR = "PPR285723";   // its EuropePMC preprint id (10 citations)
	static final String CITED_PUB = "10.1136/bmj.n579"; // its published version (heavily cited)
	static final String PMID  = "41888721";
	static final String PMCID = "PMC13141338";
	static final String PPR   = "PPR1270844";

	static final String UA = "bibliographic-api-client/1.0 (fixture-recorder)";

	static int captured = 0;
	static int failed = 0;
	static String FILTER = null;

	public static void main(String[] args) throws Exception {
		Path secretsFile = Paths.get(args.length > 0 ? args[0]
				: System.getProperty("user.home") + "/.private/apis/secrets.prop");
		Path outDir = Paths.get(args.length > 1 ? args[1] : "src/test/resources/fixtures");
		String only = args.length > 2 ? args[2] : null;
		FILTER = only;

		Properties secrets = new Properties();
		try (InputStream is = Files.newInputStream(secretsFile)) { secrets.load(is); }
		String email = secrets.getProperty("developer-email");
		String tool  = secrets.getProperty("app-id");

		HttpClient http = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1) // EPMC drops JDK HttpClient HTTP/2 conns ("EOF reached")
				.connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();

		String EPMC = "https://www.ebi.ac.uk/europepmc/webservices/rest/";
		String XC = "https://api.crossref.org/works/";
		String OC = "https://api.opencitations.net/index/v2/";
		String UPW = "https://api.unpaywall.org/v2/";
		String BID = "https://api.biorxiv.org/";
		String IDCONV = "https://pmc.ncbi.nlm.nih.gov/tools/idconv/api/v1/articles/";

		// CrossRef: work JSON embeds the reference list, so fetch covers cites too
		rec(http, outDir, "crossref/work-published.json", XC + enc(PUB_DOI));
		rec(http, outDir, "crossref/work-preprint.json",  XC + enc(PREP_DOI));
		rec(http, outDir, "crossref/work-cited.json",     XC + enc(CITED_DOI));

		// EuropePMC: search (MED + PPR), citations, references (PMC + PPR)
		rec(http, outDir, "europepmc/search-med.json",
				EPMC + "search?query=" + enc("DOI:\"" + PUB_DOI + "\"") + "&format=json&resultType=core");
		rec(http, outDir, "europepmc/search-ppr.json",
				EPMC + "search?query=" + enc("DOI:\"" + PREP_DOI + "\"") + "&format=json&resultType=core");
		rec(http, outDir, "europepmc/citations-ppr.json", EPMC + "PPR/" + CITED_PPR + "/citations?format=json");
		rec(http, outDir, "europepmc/references-pmc.json", EPMC + "PMC/" + PMCID + "/references?format=json");
		rec(http, outDir, "europepmc/references-ppr.json", EPMC + "PPR/" + PPR + "/references?format=json");

		// Unpaywall: OA record + preprint record (both real for this corpus)
		rec(http, outDir, "unpaywall/oa-published.json", UPW + enc(PUB_DOI) + "?email=" + enc(email));
		rec(http, outDir, "unpaywall/preprint.json",     UPW + enc(PREP_DOI) + "?email=" + enc(email));

		// OpenCitations v2 wants the id as a handle: prefix the DOI with the doi: scheme
		rec(http, outDir, "opencitations/citations-published.json", OC + "citations/doi:" + CITED_PUB);
		rec(http, outDir, "opencitations/references-cited.json",    OC + "references/doi:" + CITED_DOI);

		// PMCID idconv: doi->pmcid, pmid->pmcid, and a clean miss
		rec(http, outDir, "pmcid/doi.json",  idconv(IDCONV, tool, email, "doi", PUB_DOI));
		rec(http, outDir, "pmcid/pmid.json", idconv(IDCONV, tool, email, "pmid", PMID));
		rec(http, outDir, "pmcid/miss.json", idconv(IDCONV, tool, email, "doi", "10.1186/s12879-026-99999-x"));

		// MedRxiv: details under both DOI prefixes, one window page. No /pubs fixture:
		// medRxiv dropped preprint-to-published mapping (IdMapper) because /pubs is
		// blind to the 10.64898/ prefix and would answer "not published" wrongly.
		rec(http, outDir, "medrxiv/details-newprefix.json", BID + "details/medrxiv/" + PREP_DOI);
		rec(http, outDir, "medrxiv/details-oldprefix.json", BID + "details/medrxiv/" + CITED_DOI);
		rec(http, outDir, "medrxiv/window.json", BID + "details/medrxiv/2021-02-09/2021-02-09/0");

		// Entrez (eutils): esearch by DOI, efetch the canonical record, elink references.
		// Same db/term/id/linkname parameters the client builds for the corpus.
		String EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/";
		String auth = "&tool=" + enc(tool) + "&email=" + enc(email)
				+ "&api_key=" + enc(secrets.getProperty("pubmed-apikey", ""));
		rec(http, outDir, "entrez/esearch-doi.xml",
				EUTILS + "esearch.fcgi?db=pubmed&retmode=xml&term=" + enc("\"" + PUB_DOI + "\"[DOI]") + auth);
		rec(http, outDir, "entrez/efetch-pmid.xml",
				EUTILS + "efetch.fcgi?db=pubmed&retmode=xml&rettype=abstract&id=" + PMID + auth);
		rec(http, outDir, "entrez/elink-refs.xml",
				EUTILS + "elink.fcgi?dbfrom=pubmed&db=pubmed&retmode=xml"
						+ "&linkname=pubmed_pubmed_refs&id=" + PMID + auth);

		System.out.println("Captured " + captured + " fixtures (" + failed + " failed) into " + outDir.toAbsolutePath());
		if (failed > 0) System.exit(1);
	}

	static String idconv(String base, String tool, String email, String idType, String id) {
		return base + "?tool=" + enc(tool) + "&email=" + enc(email)
				+ "&format=json&ids=" + enc(id) + "&idtype=" + idType;
	}

	/** GET url, write raw bytes to outDir/name on 2xx; count success/failure */
	static void rec(HttpClient http, Path outDir, String name, String url) {
		if (FILTER != null && !name.startsWith(FILTER)) return;
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.header("User-Agent", UA)
					.timeout(Duration.ofSeconds(60))
					.GET().build();
			HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			int code = resp.statusCode();
			byte[] body = resp.body();
			if (code < 200 || code >= 300 || body == null || body.length == 0) {
				failed++;
				System.out.println("FAIL " + name + "  HTTP " + code
						+ " (" + (body == null ? 0 : body.length) + " bytes)  " + url);
				return;
			}
			Path out = outDir.resolve(name);
			Files.createDirectories(out.getParent());
			Files.write(out, body);
			captured++;
			System.out.println("  ok " + name + "  " + body.length + " bytes");
		} catch (IOException | InterruptedException e) {
			failed++;
			System.out.println("FAIL " + name + "  " + e.getClass().getSimpleName() + ": " + e.getMessage());
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
		}
	}

	/** percent-encode a whole path/query segment the way the clients' encode() does */
	static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
