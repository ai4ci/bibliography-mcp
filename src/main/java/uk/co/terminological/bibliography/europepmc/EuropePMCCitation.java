package uk.co.terminological.bibliography.europepmc;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.europepmc.EuropePMCClient.DataSources;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.PrintReference;

/*

id: "31481663",
source: "MED",
citationType: "research-article; journal article",
title: "Pituitary cell translation and secretory capacities are enhanced cell autonomously by the transcription factor Creb3l2.",
authorString: "Khetchoumian K, Balsalobre A, Mayran A, Christian H, Chénard V, St-Pierre J, Drouin J.",
journalAbbreviation: "Nat Commun",
pubYear: 2019,
volume: "10",
issue: "1",
pageInfo: "3960",
citedByCount: 0 

*/

//TODO: change API to make a first class citation object
//TODO: get this to conform

public class EuropePMCCitation extends ExtensibleJson implements PrintReference, RecordReference {

	public EuropePMCCitation(JsonNode node) { super(node); }
	
	public Optional<String> getIdentifier() {return this.asString("id").map(s->s.replaceAll("^PMC", ""));}
	public Optional<DataSources> getSource() {return this.asString("source").map(DataSources::valueOf);}
	/** the title, or the raw citation string when the citing work could not be matched */
	public Optional<String> getTitle() {
		return this.asString("title")
				.or(() -> this.asString("unstructuredInformation"))
				.map(uk.co.terminological.bibliography.record.PlainText::of);
	}
	public Optional<String> getAuthorString() {return this.asString("authorString");}
	public Optional<String> getJournal() {return this.asString("journalAbbreviation");}
	public Optional<String> getIssue() {return this.asString("issue");}
	public Optional<String> getVolume() {return this.asString("volume");}
	public Optional<Long> getYear() {return this.asLong("pubYear");}
	public Optional<String> getPage() {return this.asString("pageInfo");}
	public Optional<String> getEssn() {return this.asString("essn");}
	public Optional<String> getIssn() {return this.asString("issn");}
	
	public Optional<Long> getCitedByCount() {return this.asLong("citedByCount");}

	@Override
	public IdType getIdentifierType() {
		if (!getSource().isPresent()) return IdType.UNK;
		if (getSource().get().equals(DataSources.MED)) return IdType.PMID;
		if (getSource().get().equals(DataSources.PMC)) return IdType.PMCID;
		return IdType.UNK;
	}

	@Override
	public Optional<String> getFirstAuthorName() {
		return getAuthorString().map(s -> s.split(",")[0]);
	}
	
}
