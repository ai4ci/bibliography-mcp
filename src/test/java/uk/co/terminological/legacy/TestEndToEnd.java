package uk.co.terminological.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.slf4j.LoggerFactory;

import uk.co.terminological.bibliography.api.BibliographicApiException;
import uk.co.terminological.bibliography.api.crossref.CrossRefClient;
import uk.co.terminological.bibliography.api.crossref.CrossRefSingleResult;
import uk.co.terminological.bibliography.api.crossref.CrossRefWork;
import uk.co.terminological.bibliography.api.entrez.EntrezClient;
import uk.co.terminological.bibliography.api.pmcidconv.PMCIDClient;
import uk.co.terminological.bibliography.api.unpaywall.UnpaywallClient;
import uk.co.terminological.bibliography.record.IdType;

public class TestEndToEnd {

	public static void main(String[] args)
			throws IOException, BibliographicApiException, ParseException {

		BasicConfigurator.configure();
		org.apache.log4j.Logger.getRootLogger()
			.setLevel(Level.TRACE);
		var log = LoggerFactory.getLogger(TestEndToEnd.class);

		log.debug("Debugging");

		var prop = System.getProperties();
		prop.load(
			Files.newInputStream(
				Paths.get(
					prop.getProperty("user.home"),
					"/.private/apis/secrets.prop"
				)
			)
		);

		// String xrefToken = prop.getProperty("crossref.clickthroughtoken");
		var developerEmail = prop.getProperty("developer-email");
		var pubmedApiToken = prop.getProperty("pubmed-apikey");
		var appId = prop.getProperty("app-id");

		var ehcache = Paths.get("/tmp/ehcache/test124");
		Files.createDirectories(ehcache.getParent());

		var pubmed = EntrezClient.create(
			pubmedApiToken,
			appId,
			developerEmail,
			ehcache.resolve("pubmed")
		);
		var mapper = PMCIDClient
			.create(appId, developerEmail, ehcache.resolve("idconv"));
		var xref = CrossRefClient.create(developerEmail, ehcache.resolve("xref"));
		var unpaywall = UnpaywallClient
			.create(developerEmail, ehcache.resolve("unpaywall"));

		var result = pubmed.buildQuery()
			.searchTerm("machine learning")
			.limit(0, 50)
			// since()/until() were replaced by between(): eSearch ignores a date range
			// unless both ends are supplied, so the one-sided builders were a trap
			.between(
				Optional.of(LocalDate.of(2016, 01, 1)),
				Optional.of(LocalDate.of(2017, 01, 1))
			)
			.execute()
			.get();

		List<String> dois2 = pubmed.getPMEntriesByPMIds(
			result.getIds()
				.collect(Collectors.toList())
		)
			.stream()
			.flatMap(
				pme -> pme.getDoi()
					.map(Stream::of)
					.orElse(Stream.empty())
			)
			.collect(Collectors.toList());
		var dois = mapper.getDoisByIdAndType(
			result.getIds()
				.collect(Collectors.toList()),
			IdType.PMID
		);

		List<String> tmp = new ArrayList<>(dois2);
		tmp.removeAll(dois.values());
		System.out.println("Extra dois found by pubmed");
		tmp.forEach(System.out::println);

		List<String> tmp2 = new ArrayList<>(dois.values());
		tmp2.removeAll(dois2);
		System.out.println("Extra dois found by id mapper");
		tmp2.forEach(System.out::println);

		System.out.println("Unpaywall entries");
		unpaywall.getUnpaywallByDois(dois2)
			.stream()
			.forEach(
				res -> System.out.println(
					res.getIdentifier()
						.orElse("?") + "\t" + res.getTitle() + "\t" + res.getPdfUri()
				)
			);

		Stream<CrossRefWork> work = dois2.stream()
			.flatMap(
				doi -> xref.getByDoi(doi)
					.map(Stream::of)
					.orElse(Stream.empty())
			)
			.map(CrossRefSingleResult::getWork);
		work.forEach(w -> System.out.println(w.getTitle()));
		// https://academic.oup.com/bioinformatics/article-pdf/33/6/863/25147932/btw768.pdf
		var work2 = xref.getByDoi("10.1093/bioinformatics/btw768")
			.get()
			.getWork();
		// System.out.println(work.journalAbstract.get());
		work2.getReferences()
			.forEach(
				r -> System.out.println(
					r.getIdentifier()
						.orElse("?") + "\t"
							+ r.getTitle()
								.orElse("No title")
				)
			);
		System.exit(0);
	}

}
