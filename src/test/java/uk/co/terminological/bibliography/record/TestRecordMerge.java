package uk.co.terminological.bibliography.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * feat-007: when several discovery APIs describe the same work the merged record must be robust and, above all,
 * predictable — the same inputs always give the same answer, whatever order the APIs answered in. Curated records
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
		var crossref = record(
			"crossref",
			"journal-article",
			"Title from CrossRef",
			"Abstract from CrossRef"
		);
		var europepmc = record(
			"europepmc",
			"Journal Article",
			"Title from EuropePMC",
			"Abstract from EuropePMC"
		);

		var ab = MergedRecord.from(crossref)
			.merge(europepmc);
		var ba = MergedRecord.from(europepmc)
			.merge(crossref);

		assertEquals(ab.getTitle(), ba.getTitle());
		assertEquals(ab.getAbstract(), ba.getAbstract());
		assertEquals(ab.getJournal(), ba.getJournal());
		assertEquals(ab.getDate(), ba.getDate());
		assertEquals(ab.getPublicationType(), ba.getPublicationType());
	}

	@Test
	public void perFieldPriorityPicksTheRightSourceForEachField() {
		var crossref = record(
			"crossref",
			"journal-article",
			"Title from CrossRef",
			"Abstract from CrossRef"
		);
		var europepmc = record(
			"europepmc",
			"Journal Article",
			"Title from EuropePMC",
			"Abstract from EuropePMC"
		);

		var merged = MergedRecord.from(crossref)
			.merge(europepmc);

		// title/date prefer CrossRef; abstracts prefer Europe PMC
		assertEquals(
			"Title from CrossRef",
			merged.getTitle()
				.orElse(null)
		);
		assertEquals(
			"Abstract from EuropePMC",
			merged.getAbstract()
				.orElse(null)
		);
	}

	@Test
	public void lowerPrioritySourceStillFillsAGap() {
		var crossref = recordWithout(
			"crossref",
			"journal-article",
			null,
			"Abstract from CrossRef"
		);
		var europepmc = record(
			"europepmc",
			"Journal Article",
			"Title from EuropePMC",
			"Abstract from EuropePMC"
		);

		var merged = MergedRecord.from(crossref)
			.merge(europepmc);

		// non-empty beats priority, otherwise the value would be lost
		assertEquals(
			"Title from EuropePMC",
			merged.getTitle()
				.orElse(null)
		);
	}

	@Test
	public void resultDoesNotDependOnWhichRecordWasFirst() {
		var a = record(
			"entrez",
			"Journal Article",
			"Same title",
			"Entrez abstract"
		);
		var b = record(
			"openalex",
			"Journal Article",
			"Same title",
			"OpenAlex abstract"
		);
		var c = record(
			"crossref",
			"journal-article",
			"Same title",
			"CrossRef abstract"
		);

		var one = MergedRecord.from(a)
			.merge(b)
			.merge(c);
		var two = MergedRecord.from(c)
			.merge(a)
			.merge(b);

		assertEquals(one.getAbstract(), two.getAbstract());
		assertEquals(one.getPublisher(), two.getPublisher());
	}

	// ---- robustness: authors ----

	@Test
	public void mergedAuthorsAreDeduplicated() {
		var a = recordWithAuthors(
			"crossref",
			"journal-article",
			List.of(author("Smith"), author("Jones"))
		);
		var b = recordWithAuthors(
			"europepmc",
			"Journal Article",
			List.of(author("Smith"), author("Brown"))
		);

		var merged = MergedRecord.from(a)
			.merge(b);

		var names = merged.getAuthors()
			.stream()
			.map(Author::getLastName)
			.toList();
		assertEquals(List.of("Smith", "Jones", "Brown"), names);
	}

	@Test
	public void authorsWithTheSameOrcidAreDeduplicatedDespiteNameDifferences() {
		var a = recordWithAuthors(
			"crossref",
			"journal-article",
			List.of(authorWithOrcid("Smith", "0000-0002-5504-7768"))
		);
		var b = recordWithAuthors(
			"europepmc",
			"Journal Article",
			List.of(authorWithOrcid("Smith, R", "0000-0002-5504-7768"))
		);

		assertEquals(
			1,
			MergedRecord.from(a)
				.merge(b)
				.getAuthors()
				.size()
		);
	}

	@Test
	public void identifiersAndLicencesAreUnions() {
		var a = record("crossref", "journal-article", "T", "A");
		var b = record("europepmc", "Journal Article", "T", "A");

		var merged = MergedRecord.from(a)
			.merge(b);

		assertTrue(
			merged.getIdentifiers()
				.stream()
				.anyMatch(i -> i.getIdentifierType() == IdType.DOI)
		);
		assertEquals(
			2,
			merged.getProvenances()
				.size()
		);
	}

	@Test
	public void mergingAnAlreadyMergedRecordAbsorbsItsContributions() {
		// BiblioFacade.fetch merges per-API results into one record, so a
		// MergedRecord gets merged into another MergedRecord. Flattening it at
		// that point silently discards every contribution but the first.
		var twoApis = MergedRecord
			.from(record("crossref", "journal-article", "T", "Crossref abstract"))
			.merge(
				record("europepmc", "Journal Article", "T", "EuropePMC abstract")
			);
		var third = MergedRecord.from(
			record("openalex", "journal-article", "T", "OpenAlex abstract")
		);

		var merged = third.merge(twoApis);

		assertEquals(
			3,
			merged.getProvenances()
				.size()
		);
		// and the discarded contributions must still influence resolution
		assertEquals(
			"EuropePMC abstract",
			merged.getAbstract()
				.orElse(null)
		);
	}

	@Test
	public void pdfLocationPrefersOpenAlexOverCrossRef() {
		// the policy cannot rank a source that reports no provenance, which is why
		// OpenAlexRecord and EntrezEntry now carry one. OpenAlex took over this role
		// from Unpaywall: it publishes the open-access link in the same payload as the
		// metadata, so naming it here is the whole of the replacement
		var crossref = (Stub) record("crossref", "journal-article", "T", "A");
		var openalex = ((Stub) record("openalex", "journal-article", "T", "A"))
			.withPdf("https://example.org/oa.pdf");
		crossref.withPdf("https://example.org/paywalled.pdf");

		var merged = MergedRecord.from(crossref)
			.merge(openalex);

		assertEquals(
			"https://example.org/oa.pdf",
			merged.getPdfUri()
				.map(URI::toString)
				.orElse(null)
		);
	}

	@Test
	public void aPostedContentRecordIsOwnedByItsPreprintServer() {
		// the server is the source of record for its own preprint and it updates
		// first, so it wins even though the indexed services usually outrank it
		var preprint = record(
			"medrxiv",
			"posted-content",
			"Title from medRxiv",
			"Abstract from medRxiv"
		);
		var indexed = record(
			"europepmc",
			"posted-content",
			"Title from Europe PMC",
			"Abstract from Europe PMC"
		);

		var merged = MergedRecord.from(preprint)
			.merge(indexed);

		assertEquals(
			"Title from medRxiv",
			merged.getTitle()
				.orElse(null)
		);
		assertEquals(
			"Abstract from medRxiv",
			merged.getAbstract()
				.orElse(null)
		);
	}

	@Test
	public void anIndexedServiceStillOutranksAPreprintServerForAPublishedWork() {
		// the preprint servers are only promoted for their own kind of record
		var serverRecord = record(
			"medrxiv",
			"journal-article",
			"Title from the server",
			"Abstract from the server"
		);
		var indexed = record(
			"europepmc",
			"Journal Article",
			"Title from Europe PMC",
			"Abstract from Europe PMC"
		);

		var merged = MergedRecord.from(serverRecord)
			.merge(indexed);

		assertEquals(
			"Title from Europe PMC",
			merged.getTitle()
				.orElse(null)
		);
		assertEquals(
			"Abstract from Europe PMC",
			merged.getAbstract()
				.orElse(null)
		);
	}

	@Test
	public void aPreprintStillSuppliesWhatTheIndexedServicesLack() {
		var preprint = record(
			"medrxiv",
			"posted-content",
			"Title from medRxiv",
			"Abstract from medRxiv"
		);
		var indexed = recordWithout(
			"europepmc",
			"Journal Article",
			"Title from Europe PMC",
			null
		);

		var merged = MergedRecord.from(preprint)
			.merge(indexed);

		assertEquals(
			"Abstract from medRxiv",
			merged.getAbstract()
				.orElse(null)
		);
	}

	// ---- curated records are authoritative and kept distinct ----

	@Test
	public void aZoteroRecordIsNotTreatedAsAuthoritative() {
		// A zotero item may be scraped from a publisher page and broken, or hand
		// repaired; we cannot tell which, so it is ranked by the same per-field
		// policy as every other source rather than winning unconditionally.
		var api = record(
			"crossref",
			"journal-article",
			"Title from CrossRef",
			"Abstract from CrossRef"
		);
		var zotero = curated("Zotero title", null);

		var merged = MergedRecord.from(api)
			.merge(zotero);

		assertEquals(
			"Title from CrossRef",
			merged.getTitle()
				.orElse(null)
		);
	}

	@Test
	public void aZoteroOnlyValueIsStillUsed() {
		// ...but nothing is thrown away: where the APIs have no value, zotero's stands
		var api = record(
			"crossref",
			"journal-article",
			"Title from CrossRef",
			null
		);
		var zotero = curated("Zotero title", "Zotero abstract");

		var merged = MergedRecord.from(api)
			.merge(zotero);

		assertEquals(
			"Zotero abstract",
			merged.getAbstract()
				.orElse(null)
		);
	}

	@Test
	public void curatedGapsAreFilledFromApis() {
		var api = record(
			"crossref",
			"journal-article",
			"Title from CrossRef",
			"Abstract from CrossRef"
		);
		var zotero = curated(null, null); // zotero has no abstract of its own

		var merged = MergedRecord.from(api)
			.merge(zotero);

		assertEquals(
			"Abstract from CrossRef",
			merged.getAbstract()
				.orElse(null)
		);
	}

	@Test
	public void curatedRecordsAreIdentifiableInTheProvenance() {
		var api = record("crossref", "journal-article", "T", "A");
		var curated = curated("Curated title", null);

		var merged = MergedRecord.from(api)
			.merge(curated);

		assertTrue(
			merged.getProvenances()
				.stream()
				.anyMatch(Provenance::isCurated)
		);
		assertTrue(
			merged.getProvenances()
				.stream()
				.anyMatch(p -> !p.isCurated())
		);
	}

	// ---- stand-ins ----

	static Record record(
			String source, String nativeType, String title, String abstractText
	) {
		return new Stub(
				source, nativeType, title, abstractText, List.of(), false,
				"Some Journal", DOI
		);
	}

	static Record recordWithout(
			String source, String nativeType, String title, String abstractText
	) {
		return new Stub(
				source, nativeType, title, abstractText, List.of(), false, null, DOI
		);
	}

	static Record recordWithAuthors(
			String source, String nativeType, List<Author> authors
	) {
		return new Stub(source, nativeType, "T", "A", authors, false, null, DOI);
	}

	static Record curated(String title, String abstractText) {
		return new Stub(
				"zotero", "journalArticle", title, abstractText, List.of(), true,
				null, DOI
		);
	}

	static Author author(String lastName) {
		return new StubAuthor(lastName, null);
	}

	static Author authorWithOrcid(String lastName, String orcid) {
		return new StubAuthor(lastName, orcid);
	}

	static class StubAuthor implements Author {
		private final String lastName;
		private final String orcid;

		StubAuthor(String lastName, String orcid) {
			this.lastName = lastName;
			this.orcid = orcid;
		}

		@Override
		public Optional<String> getORCID() {
			return Optional.ofNullable(this.orcid);
		}
		@Override
		public Optional<String> getFirstName() { return Optional.of("Robert"); }
		@Override
		public String getLastName() { return this.lastName; }
		@Override
		public Optional<String> getInitials() { return Optional.of("R"); }
		@Override
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

		Stub(
				String source, String nativeType, String title, String abstractText,
				List<Author> authors, boolean curated, String journal, String id
		) {
			this.provenance = curated ? Provenance.curated(source)
				: Provenance.of(source, nativeType);
			this.publicationType = nativeType;
			this.title = title;
			this.abstractText = abstractText;
			this.authors = authors;
			this.journal = journal;
			this.id = id;
		}

		@Override
		public Optional<String> getIdentifier() { return Optional.of(this.id); }
		@Override
		public IdType getIdentifierType() { return IdType.DOI; }
		@Override
		public List<RecordReference> getOtherIdentifiers() { return List.of(); }
		@Override
		public List<Author> getAuthors() { return this.authors; }
		@Override
		public Stream<String> getLicenses() { return Stream.of("licence-a"); }
		@Override
		public Optional<String> getAbstract() {
			return Optional.ofNullable(this.abstractText);
		}
		@Override
		public Optional<String> getTitle() {
			return Optional.ofNullable(this.title);
		}
		@Override
		public Optional<String> getJournal() {
			return Optional.ofNullable(this.journal);
		}
		@Override
		public Optional<LocalDate> getDate() {
			return Optional.of(LocalDate.of(2019, 1, 12));
		}
		@Override
		public Optional<URI> getPdfUri() {
			return this.pdf == null ? Optional.empty()
				: Optional.of(URI.create(this.pdf));
		}
		@Override
		public Provenance getProvenance() { return this.provenance; }
		@Override
		public Optional<String> getPublicationType() {
			return Optional.ofNullable(this.publicationType);
		}
		Stub withPdf(String url) {
			this.pdf = url;
			return this;
		}
	}
}
