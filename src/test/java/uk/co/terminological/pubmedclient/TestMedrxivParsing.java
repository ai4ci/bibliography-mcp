package uk.co.terminological.pubmedclient;

import java.io.IOException;

import uk.co.terminological.bibliography.medrxiv.MedrxivSearch;
import uk.co.terminological.fluentxml.Xml;
import uk.co.terminological.fluentxml.XmlException;

public class TestMedrxivParsing {

	public static void main(String[] args) throws IOException, XmlException {
		// TODO Auto-generated method stub

		System.out.println("\n==SEARCH HTML=============================\n");
		var is = ClassLoader
			.getSystemResourceAsStream("medrxivSearch.html");
		var xml = Xml.fromHtmlStream(is);
		var response = new MedrxivSearch(xml.content());
		response.stream()
			.forEach(item -> {
				System.out.println(
					"\n========================================\n" + item.getTitle()
				);
				// System.out.println("cited by: "+item.getCitedByCount());
				System.out.println("date: " + item.getDate());
				System.out.println("id: " + item.getIdentifier());
				System.out.println("idtype: " + item.getIdentifierType());
				System.out.println("journal: " + item.getJournal());
				System.out.println("pdf uri: " + item.getPdfUri());
				System.out.println("jats uri: " + item.getJatsUri());
				// System.out.println("references: "+item.getReferencesCount());
				// System.out.println("score: "+item.getScore());
				System.out.println("title: " + item.getTitle());
				// System.out.println("tect mining uri: "+item.getTextMiningUri());
				System.out.println("AUTHORS:");
				item.getAuthors()
					.forEach(a -> {
						System.out.println("AUTHOR:");
						System.out.println("first name: " + a.getFirstName());
						System.out.println("last name: " + a.getLastName());
					});

			});

		System.out.println("\n==SEARCH HTML=============================\n");

	}

}
