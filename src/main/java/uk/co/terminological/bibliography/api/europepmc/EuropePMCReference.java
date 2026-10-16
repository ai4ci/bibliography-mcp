package uk.co.terminological.bibliography.api.europepmc;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.api.europepmc.EuropePMCClient.DataSources;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.PrintReference;
import uk.co.terminological.bibliography.record.RecordReference;

/*
id: "7569999",
source: "MED",
citationType: "JOURNAL ARTICLE",
title: "Quantitative monitoring of gene expression patterns with a complementary DNA microarray.",
authorString: "Schena M, Shalon D, Davis RW, Brown PO.",
journalAbbreviation: "Science",
issue: "5235",
pubYear: 1995,
volume: "270",
pageInfo: "467-470",
citedOrder: 1,
match: "Y",
essn: "1095-9203",
issn: "0036-8075"
 */

public class EuropePMCReference extends ExtensibleJson
		implements PrintReference, RecordReference {

	public EuropePMCReference(JsonNode node) {
		super(node);
	}

	public Optional<String> getIdentifier() {
		return this.asString("id")
			.map(s -> s.replaceAll("^PMC", ""));
	}
	public Optional<DataSources> getSource() {
		return this.asString("source")
			.map(DataSources::valueOf);
	}
	/**
	 * The title, or the raw citation string when Europe PMC could not match the reference to a record. Unmatched rows carry
	 * `unstructuredInformation` and `match: N`, and reading only `title` left 17 of 31 references of a real preprint with
	 * no way to describe them.
	 */
	public Optional<String> getTitle() {
		return this.asString("title")
			.or(() -> this.asString("unstructuredInformation"))
			.map(uk.co.terminological.bibliography.record.PlainText::of);
	}
	public Optional<String> getAuthorString() {
		return this.asString("authorString");
	}
	public Optional<String> getJournal() {
		return this.asString("journalAbbreviation");
	}
	public Optional<String> getIssue() { return this.asString("issue"); }
	public Optional<String> getVolume() { return this.asString("volume"); }
	public Optional<Long> getYear() { return this.asLong("pubYear"); }
	public Optional<String> getPage() { return this.asString("pageInfo"); }
	public Optional<String> getEssn() { return this.asString("essn"); }
	public Optional<String> getIssn() { return this.asString("issn"); }

	public Optional<String> getCitationIndex() {
		return this.asString("citedOrder");
	}

	@Override
	public IdType getIdentifierType() {
		if (!getSource().isPresent()) return IdType.UNK;
		if (getSource().get()
			.equals(DataSources.MED)) return IdType.PMID;
		if (getSource().get()
			.equals(DataSources.PMC)) return IdType.PMCID;
		return IdType.UNK;
	}

	@Override
	public Optional<String> getFirstAuthorName() {
		return getAuthorString().map(s -> s.split(",")[0]);
	}
}
