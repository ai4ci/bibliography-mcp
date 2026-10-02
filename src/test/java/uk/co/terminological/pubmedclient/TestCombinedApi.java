package uk.co.terminological.pubmedclient;

import java.io.IOException;
import java.nio.file.Paths;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.slf4j.LoggerFactory;

import uk.co.terminological.bibliography.mcp.Analysis;
import uk.co.terminological.bibliography.mcp.BiblioFacade;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;

public class TestCombinedApi {
	// [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)

	public static void main(String[] args)
			throws IOException, ClassNotFoundException {

		BasicConfigurator.configure();
		org.apache.log4j.Logger.getRootLogger()
			.setLevel(Level.DEBUG);
		var log = LoggerFactory.getLogger(TestCombinedApi.class);

		log.debug("Debugging");

		var api = new BiblioFacade(
				Paths.get(
					System.getProperty("user.home") + "/.private/apis/secrets.prop"
				), Paths.get("/tmp/ehcache/test125")
		);
		// api.disableCache();

		api.withSearchLimit(10);
		var session = api.getSession();
		session.collectSearch("covid 19 database");
		session.getRecords()
			.forEach(
				(k, r) -> System.out.println(
					k + " " + r.getTitle()
						.orElse("")
				)
			);

		session
			.fetchRecords(
				java.util.Set.of(
					Builder
						.recordReference(IdType.DOI, "10.64898/2025.12.12.25342129")
				)
			);
		session.getFacade()
			.citesReferences(
				java.util.Set.of(
					Builder
						.recordReference(IdType.DOI, "10.64898/2025.12.12.25342129")
				)
			)
			.forEach(CitationLink::print);

		System.out.println("REPOSITORY");
		session.addSingle(IdType.DOI, "10.64898/2025.12.12.25342129");
		session.expandUpCitationGraph(1000);
		session.expandDownCitationGraph(60);
		session.fetchMissingRecords();

		System.out.println("RECORDS");
		session.getRecords()
			.forEach(
				(k, r) -> System.out.println(
					k + " " + r.getTitle()
						.orElse("")
				)
			);

		System.out.println("CITATIONS");
		session.uuidCitations()
			.forEach(
				t -> System.out.println(t.getFirst() + " -> " + t.getSecond())
			);

		System.out.println("PAGERANK");
		System.out.println(Analysis.pageRank(session));

		session.save("/tmp/testRepo.ser");

		var session2 = api.loadSession("/tmp/testRepo.ser");
		System.out.println(session2.status());

		System.exit(0);
	}

}
