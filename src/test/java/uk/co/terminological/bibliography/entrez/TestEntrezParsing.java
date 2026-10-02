package uk.co.terminological.pubmedclient;

import java.io.IOException;
import java.io.InputStream;

import uk.co.terminological.bibliography.entrez.EntrezEntries;
import uk.co.terminological.fluentxml.Xml;
import uk.co.terminological.fluentxml.XmlException;

public class TestEntrezParsing {

	public static void main(String[] args) throws IOException, XmlException {
		// TODO Auto-generated method stub

		InputStream is = ClassLoader.getSystemResourceAsStream("efetch.xml");
		Xml xml = Xml.fromStream(is);
		EntrezEntries  response = new EntrezEntries(xml.content());
		response.stream().forEach(
				item -> {
					System.out.println("\n========================================\n"+item.getTitle());
					System.out.println("abs: "+item.getAbstract());
					//System.out.println("cited by: "+item.getCitedByCount());
					System.out.println("date: "+item.getDate());
					System.out.println("first author: "+item.getFirstAuthorName());
					System.out.println("id: "+item.getIdentifier());
					System.out.println("idtype: "+item.getIdentifierType());
					System.out.println("issue: "+item.getIssue());
					System.out.println("journal: "+item.getJournal());
					System.out.println("page: "+item.getPage());
					System.out.println("pdf uri: "+item.getPdfUri());
					//System.out.println("references: "+item.getReferencesCount());
					//System.out.println("score: "+item.getScore());
					System.out.println("title: "+item.getTitle());
					//System.out.println("tect mining uri: "+item.getTextMiningUri());
					System.out.println("vol: "+item.getVolume());
					System.out.println("year: "+item.getYear());
					System.out.println("AUTHORS:");
					item.getAuthors().forEach(a -> {
						System.out.println("AUTHOR:");
						System.out.println("first name: "+a.getFirstName());
						System.out.println("last name: "+a.getLastName());
						System.out.println("initials name: "+a.getInitials());
						System.out.println("orcid name: "+a.getORCID());
					});
					System.out.println("CITATIONS:");
					/*item.getCitations().forEach(r -> {
						System.out.println("CITATION:");
						System.out.println(r.getFirstAuthorName());
						System.out.println(r.getIdentifier());
						System.out.println(r.getIdentifierType());
						System.out.println(r.getIssue());
						System.out.println(r.getJournal());
						System.out.println(r.getPage());
						System.out.println(r.getTitle());
						System.out.println(r.getVolume());
						System.out.println(r.getYear());
					});*/
				}
				);
		
		
		
	}

}
