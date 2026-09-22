package uk.co.terminological.pubmedclient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors;

import org.apache.log4j.BasicConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.terminological.bibliography.BibliographicApiException;
import uk.co.terminological.bibliography.entrez.EntrezClient;
import uk.co.terminological.bibliography.entrez.EntrezClient.Command;
import uk.co.terminological.bibliography.entrez.EntrezClient.Database;
import uk.co.terminological.bibliography.entrez.EntrezEntry;

public class TestPubMedRestClient {

	public static Logger logger = LoggerFactory
		.getLogger(TestPubMedRestClient.class);

	public static void main(String[] args)
			throws BibliographicApiException, IOException {
		BasicConfigurator.configure();

		// Taunton[Affiliation] AND UK[Affiliation] AND NHS[Affilitation]

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

		var restClient = EntrezClient
			.create(pubmedApiToken, appId, developerEmail);
		restClient.debugMode();

		var result = restClient.buildSearchQuery("Doxapram")
			.limit(0, 10)
			.execute()
			.get();

		result.getIds()
			.forEach(System.out::println);

		var links = restClient
			.buildLinksQueryForIdsAndDatabase(
				result.getIds()
					.collect(Collectors.toSet()),
				Database.PUBMED
			)
			.command(Command.LLINKS)
			.execute()
			.get();

		links.stream()
			.forEach(System.out::println);

		var links2 = restClient
			.buildLinksQueryForIdsAndDatabase(
				result.getIds()
					.collect(Collectors.toSet()),
				Database.PUBMED
			)
			.command(Command.NEIGHBOR_SCORE)
			.execute()
			.get();

		links2.stream()
			.forEach(System.out::println);

		var entries = restClient.getPMEntriesByPMIds(
			result.getIds()
				.collect(Collectors.toSet())
		);
		entries.stream()
			.map(EntrezEntry::getTitle)
			.forEach(System.out::println);

		var entry = restClient.getPMEntryByPMId("11748933");
		entry.get()
			.getMeshHeadings()
			.forEach(System.out::println);
		entry.get()
			.getJournal()
			.ifPresent(System.out::println);

		System.out.println(
			entry.get()
				.getAbstract()
		);

	}

}
