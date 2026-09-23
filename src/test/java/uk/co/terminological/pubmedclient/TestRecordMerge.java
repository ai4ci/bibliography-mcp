package uk.co.terminological.pubmedclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.Test;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.MergedRecord;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * feat-007: when several discovery APIs describe the same work the merged
 * record must be robust and, above all, predictable — the same inputs always
 * give the same answer, whatever order the APIs answered in. Curated records
 * (Zotero) are authoritative and are never overridden by API values.
 *
 * [TESTS](/design/implementation/issues/issue-003-merged-record-resolution-is-non-deterministic.md)
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestRecordMerge {

	private static final String DOI = "10.1136/bmjqs-2018-008370";

	// ---- predictability: the acceptance test ----

	@Test
	public void resolutionIsIndependentOfInsertionOrder() {
		Record crossref = record("crossref", "journal-article", "Title from CrossRef", "Abstract from CrossRef");
		Record europepmc = record("europepmc", "Journal Article", "Title from EuropePMC", "Abstract from EuropePMC");

		MergedRecord ab = MergedRecord.from(crossref).merge(europepmc);
		MergedRecord ba = MergedRecord.from(europepmc).merge(crossref);

		assertEquals(ab.getTitle(), ba.getTitle());
		assertEquals(ab.getAbstract(), ba.getAbstract());
		assertEquals(ab.getJournal(), ba.getJournal());
		assertEquals(ab.getDate(), ba.getDate());
		assertEquals(ab.getPublicationType(), ba.getPublicationType());
	}

	@Test
	public void perFieldPriorityPicksTheRightSourceForEachField() {
		Record crossref = record("crossref", "journal-article", "Title from CrossRef", "Abstract from CrossRef");
		Record europepmc = record("europepmc", "Journal Article", "Title from EuropePMC", "Abstract from EuropePMC");

		MergedRecord merged = MergedRecord.from(crossref).merge(europepmc);

		// title/date prefer CrossRef; abstracts prefer Europe PMC
		assertEquals("Title from CrossRef", merged.getTitle().orElse(null));
		assertEquals("Abstract from EuropePMC", merged.getAbstract().orElse(null));
	}

	@Test
	public void lowerPrioritySourceStillFillsAGap() {
		Record crossref = recordWithout("crossref", "journal-article", null, "Abstract from CrossRef");
		Record europepmc = record("europepmc", "Journal Article", "Title from EuropePMC", "Abstract from EuropePMC");

		MergedRecord merged = MergedRecord.from(crossref).merge(europepmc);

		// non-empty beats priority, otherwise the value would be lost
		assertEquals("Title from EuropePMC", merged.getTitle().orElse(null));
	}

	@Test
	public void resultDoesNotDependOnWhichRecordWasFirst() {
		Record a = record("entrez", "Journal Article", "Same title", "Entrez abstract");
		Record b = record("unpaywall", "Journal Article", "Same title", "Unpaywall abstract");
		Record c = record("crossref", "journal-article", "Same title", "CrossRef abstract");

		MergedRecord one = MergedRecord.from(a).merge(b).merge(c);
		MergedRecord two = MergedRecord.from(c).merge(a).merge(b);

		assertEquals(one.getAbstract(), two.getAbstract());
		assertEquals(one.getPublisher(), two.getPublisher());
	}

	// ---- robustness: authors ----

	@Test
	public void mergedAuthorsAreDeduplicated() {
		Record a = recordWithAuthors("crossref", "journal-article", List.of(author("Smith"), author("Jones")));
		Record b = recordWithAuthors("europepmc", "Journal Article", List.of(author("Smith"), author("Brown")));

		MergedRecord merged = MergedRecord.from(a).merge(b);

		List<String> names = merged.getAuthors().stream().map(Author::getLastName).toList();
		assertEquals(List.of("Smith", "Jones", "Brown"), names);
	}

	@Test
	public void authorsWithTheSameOrcidAreDeduplicatedDespiteNameDifferences() {
		Record a = recordWithAuthors("crossref", "journal-article", List.of(authorWithOrcid("Smith", "0000-0002-5504-7768")));
		Record b = recordWithAuthors("europepmc", "Journal Article", List.of(authorWithOrcid("Smith, R", "0000-0002-5504-7768")));

		assertEquals(1, MergedRecord.from(a).merge(b).getAuthors().size());
	}

	@Test
	public void identifiersAndLicencesAreUnions() {
		Record a = record("crossref", "journal-article", "T", "A");
		Record b = record("europepmc", "Journal Article", "T", "A");

		MergedRecord merged = MergedRecord.from(a).merge(b);

		assertTrue(merged.getIdentifiers().stream().anyMatch(i -> i.getIdentifierType() == IdType.DOI));
		assertEquals(2, merged.getProvenances().size());
	}

	@Test
	public void mergingAnAlreadyMergedRecordAbsorbsItsContributions() {
		// BiblioFacade.fetch merges per-API results into one record, so a
		// MergedRecord gets merged into another MergedRecord. Flattening it at
		// that point silently discards every contribution but the first.
		MergedRecord twoApis = MergedRecord.from(record("crossref", "journal-article", "T", "Crossref abstract"))
				.merge(record("europepmc", "Journal Article", "T", "EuropePMC abstract"));
		MergedRecord third = MergedRecord.from(record("unpaywall", "journal-article", "T", "Unpaywall abstract"));

		MergedRecord merged = third.merge(twoApis);

		assertEquals(3, merged.getProvenances().size());
		// and the discarded contributions must still influence resolution
		assertEquals("EuropePMC abstract", merged.getAbstract().orElse(null));
	}

	@Test
	public void pdfLocationPrefersUnpaywallOverCrossRef() {
		// the policy cannot rank a source that reports no provenance, which is why
		// UnpaywallResult and EntrezEntry now carry one
		Stub crossref = (Stub) record("crossref", "journal-article", "T", "A");
		Stub unpaywall = ((Stub) record("unpaywall", "journal-article", "T", "A")).withPdf("https://example.org/oa.pdf");
		crossref.withPdf("https://example.org/paywalled.pdf");

		MergedRecord merged = MergedRecord.from(crossref).merge(unpaywall);

		assertEquals("https://example.org/oa.pdf", merged.getPdfUri().map(URI::toString).orElse(null));
	}

	@Test
	public void aPostedContentRecordIsOwnedByItsPreprintServer() {
		// the server is the source of record for its own preprint and it updates
		// first, so it wins even though the indexed services usually outrank it
		Record preprint = record("medrxiv", "posted-content", "Title from medRxiv", "Abstract from medRxiv");
		Record indexed = record("europepmc", "posted-content", "Title from Europe PMC", "Abstract from Europe PMC");

		MergedRecord merged = MergedRecord.from(preprint).merge(indexed);

		assertEquals("Title from medRxiv", merged.getTitle().orElse(null));
		assertEquals("Abstract from medRxiv", merged.getAbstract().orElse(null));
	}

	@Test
	public void anIndexedServiceStillOutranksAPreprintServerForAPublishedWork() {
		// the preprint servers are only promoted for their own kind of record
		Record serverRecord = record("medrxiv", "journal-article", "Title from the server", "Abstract from the server");
		Record indexed = record("europepmc", "Journal Article", "Title from Europe PMC", "Abstract from Europe PMC");

		MergedRecord merged = MergedRecord.from(serverRecord).merge(indexed);

		assertEquals("Title from Europe PMC", merged.getTitle().orElse(null));
		assertEquals("Abstract from Europe PMC", merged.getAbstract().orElse(null));
	}

	@Test
	public void aPreprintStillSuppliesWhatTheIndexedServicesLack() {
		Record preprint = record("medrxiv", "posted-content", "Title from medRxiv", "Abstract from medRxiv");
		Record indexed = recordWithout("europepmc", "Journal Article", "Title from Europe PMC", null);

		MergedRecord merged = MergedRecord.from(preprint).merge(indexed);

		assertEquals("Abstract from medRxiv", merged.getAbstract().orElse(null));
	}

	// ---- curated records are authoritative and kept distinct ----

	@Test
	public void aZoteroRecordIsNotTreatedAsAuthoritative() {
		// A zotero item may be scraped from a publisher page and broken, or hand
		// repaired; we cannot tell which, so it is ranked by the same per-field
		// policy as every other source rather than winning unconditionally.
		Record api = record("crossref", "journal-article", "Title from CrossRef", "Abstract from CrossRef");
		Record zotero = curated("Zotero title", null);

		MergedRecord merged = MergedRecord.from(api).merge(zotero);

		assertEquals("Title from CrossRef", merged.getTitle().orElse(null));
	}

	@Test
	public void aZoteroOnlyValueIsStillUsed() {
		// ...but nothing is thrown away: where the APIs have no value, zotero's stands
		Record api = record("crossref", "journal-article", "Title from CrossRef", null);
		Record zotero = curated("Zotero title", "Zotero abstract");

		MergedRecord merged = MergedRecord.from(api).merge(zotero);

		assertEquals("Zotero abstract", merged.getAbstract().orElse(null));
	}

	@Test
	public void curatedGapsAreFilledFromApis() {
		Record api = record("crossref", "journal-article", "Title from CrossRef", "Abstract from CrossRef");
		Record zotero = curated(null, null); // zotero has no abstract of its own

		MergedRecord merged = MergedRecord.from(api).merge(zotero);

		assertEquals("Abstract from CrossRef", merged.getAbstract().orElse(null));
	}

	@Test
	public void curatedRecordsAreIdentifiableInTheProvenance() {
		Record api = record("crossref", "journal-article", "T", "A");
		Record curated = curated("Curated title", null);

		MergedRecord merged = MergedRecord.from(api).merge(curated);

		assertTrue(merged.getProvenances().stream().anyMatch(Provenance::isCurated));
		assertTrue(merged.getProvenances().stream().anyMatch(p -> !p.isCurated()));
	}

	// ---- stand-ins ----

	static Record record(String source, String nativeType, String title, String abstractText) {
		return new Stub(source, nativeType, title, abstractText, List.of(), false, "Some Journal", DOI);
	}

	static Record recordWithout(String source, String nativeType, String title, String abstractText) {
		return new Stub(source, nativeType, title, abstractText, List.of(), false, null, DOI);
	}

	static Record recordWithAuthors(String source, String nativeType, List<Author> authors) {
		return new Stub(source, nativeType, "T", "A", authors, false, null, DOI);
	}

	static Record curated(String title, String abstractText) {
		return new Stub("zotero", "journalArticle", title, abstractText, List.of(), true, null, DOI);
	}

	static Author author(String lastName) { return new StubAuthor(lastName, null); }

	static Author authorWithOrcid(String lastName, String orcid) { return new StubAuthor(lastName, orcid); }

	static class StubAuthor implements Author {
		private final String lastName;
		private final String orcid;

		StubAuthor(String lastName, String orcid) { this.lastName = lastName; this.orcid = orcid; }

		public Optional<String> getORCID() { return Optional.ofNullable(orcid); }
		public Optional<String> getFirstName() { return Optional.of("Robert"); }
		public String getLastName() { return lastName; }
		public Optional<String> getInitials() { return Optional.of("R"); }
		public Stream<String> getAffiliations() { return Stream.empty(); }
	}

	static class Stub implements Record {
		private final Provenance provenance;
		private final String publicationType;
		private final String title;
		private final String abstractText;
		private final List<Author> authors;
		private final String journal;
		private final String id;
		private String pdf;

		Stub(String source, String nativeType, String title, String abstractText,
				List<Author> authors, boolean curated, String journal, String id) {
			this.provenance = curated ? Provenance.curated(source) : Provenance.of(source, nativeType);
			this.publicationType = nativeType;
			this.title = title;
			this.abstractText = abstractText;
			this.authors = authors;
			this.journal = journal;
			this.id = id;
		}

		public Optional<String> getIdentifier() { return Optional.of(id); }
		public IdType getIdentifierType() { return IdType.DOI; }
		public List<? extends RecordReference> getOtherIdentifiers() { return List.of(); }
		public List<? extends Author> getAuthors() { return authors; }
		public Stream<String> getLicenses() { return Stream.of("licence-a"); }
		public Optional<String> getAbstract() { return Optional.ofNullable(abstractText); }
		public Optional<String> getTitle() { return Optional.ofNullable(title); }
		public Optional<String> getJournal() { return Optional.ofNullable(journal); }
		public Optional<LocalDate> getDate() { return Optional.of(LocalDate.of(2019, 1, 12)); }
		public Optional<URI> getPdfUri() { return pdf == null ? Optional.empty() : Optional.of(URI.create(pdf)); }
		public Optional<Provenance> getProvenance() { return Optional.of(provenance); }
		public Optional<String> getPublicationType() { return Optional.ofNullable(publicationType); }
		Stub withPdf(String url) { this.pdf = url; return this; }
	}
}
