package uk.co.terminological.bibliography.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.MergedRecord;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * A source that is keyed by DOI still gets asked about a work the caller named by PMID.
 *
 * debt-005. CrossRef's {@code fetch} takes DOIs and CrossRef has no {@code pmid} filter — {@code filter=pmid:…} is
 * refused outright — so an agent holding only a PMID used to get a merged record missing CrossRef's publisher, type and
 * licence, with nothing in the answer saying a source had been skipped. Measured live: a fetch by DOI reaches
 * {@code [crossref, entrez, europepmc, openalex]} and the same work by PMID reaches
 * {@code [entrez, europepmc, openalex]}.
 *
 * The fix does not cost an id-mapping round trip. The sources that <em>can</em> answer to a PMID hand back the DOI as
 * one of the record's other identifiers, so the facade already holds what the silent source needed; it asks again, with
 * that, only of the sources that said nothing. A caller that asked by DOI in the first place never triggers it.
 *
 * These are facts about the facade's own plumbing rather than about any API, so they are asserted offline with clients
 * that answer from a script.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestFetchSecondAsk {

	private static final String DOI = "10.1136/bmj.n579";
	private static final String PMID = "33687922";

	private static ImmutableRecordReference ref(IdType type, String id) {
		return ImmutableRecordReference.builder()
			.identifierType(type)
			.identifier(id)
			.build();
	}

	@Test
	public void aDoiOnlySourceIsAskedAgainWithTheDoiTheOthersFound() throws Exception {
		var facade = new ScriptedFacade();

		var out = facade.fetch(List.of(ref(IdType.PMID, PMID)));

		assertEquals(
			"the answer is still keyed by the identifier the caller asked about",
			List.of(ref(IdType.PMID, PMID)), List.copyOf(out.keySet())
		);
		var record = (MergedRecord) out.values()
			.iterator()
			.next();
		var sources = record.getProvenances()
			.stream()
			.map(Provenance::getSource)
			.sorted()
			.collect(java.util.stream.Collectors.toList());
		assertEquals(
			"the DOI-keyed source's contribution should be in the merged record",
			List.of("crossref", "entrez"), sources
		);
	}

	@Test
	public void theSilentSourceIsAskedWithTheDoiAndTheOthersAreNot() throws Exception {
		var facade = new ScriptedFacade();

		facade.fetch(List.of(ref(IdType.PMID, PMID)));

		assertEquals(
			"crossref said nothing to the PMID and should have been asked again"
					+ " with the DOI: " + facade.asked,
			List.of("crossref:" + PMID, "entrez:" + PMID, "crossref:" + DOI),
			facade.asked
		);
	}

	@Test
	public void aSourceThatAnsweredIsNotAskedTwice() throws Exception {
		var facade = new ScriptedFacade();

		facade.fetch(List.of(ref(IdType.PMID, PMID)));

		assertEquals(
			"entrez answered the first time and should not be asked again",
			1,
			facade.asked.stream()
				.filter(a -> a.startsWith("entrez:"))
				.count()
		);
	}

	@Test
	public void aCallerWhoAskedByDoiCostsNoSecondAsk() throws Exception {
		var facade = new ScriptedFacade();

		facade.fetch(List.of(ref(IdType.DOI, DOI)));

		assertEquals(
			"nothing was learned that was not already asked about",
			List.of("crossref:" + DOI, "entrez:" + DOI), facade.asked
		);
	}

	@Test
	public void nothingLearnedMeansNothingAskedAgain() throws Exception {
		var facade = new ScriptedFacade();
		facade.entrezKnowsTheDoi = false;

		var out = facade.fetch(List.of(ref(IdType.PMID, PMID)));

		assertEquals(
			"there was no DOI to ask with, so the second pass has nothing to do",
			List.of("crossref:" + PMID, "entrez:" + PMID), facade.asked
		);
		assertFalse("the merged record is thinner, and honestly so", out.isEmpty());
	}

	/**
	 * Two sources, scripted: one answers to a PMID and knows the DOI, the other answers to a DOI alone.
	 *
	 * That is CrossRef and Entrez with the network taken out.
	 */
	private static class ScriptedFacade extends BiblioFacade {
		final List<String> asked = new ArrayList<>();
		boolean entrezKnowsTheDoi = true;

		ScriptedFacade() throws IOException {
			super(noApis());
			this.withFetchApis(List.of("crossref", "entrez"));
		}

		@Override
		Map<ImmutableRecordReference, ? extends Record> fetchFrom(
				String fetchApi, Collection<? extends RecordReference> ids
		) {
			Map<ImmutableRecordReference, Record> out = new HashMap<>();
			for (RecordReference id : ids) {
				this.asked.add(
					fetchApi + ":" + id.getIdentifier()
						.orElse("?")
				);
				boolean isDoi = IdType.DOI.equals(id.getIdentifierType());
				if ("crossref".equals(fetchApi) && isDoi) {
					out.put(id.asId(), new Scripted(id, "crossref", List.of()));
				}
				if ("entrez".equals(fetchApi) && !isDoi) {
					out.put(
						id.asId(), new Scripted(
								id, "entrez",
								this.entrezKnowsTheDoi ? List.of(ref(IdType.DOI, DOI))
									: List.of()
						)
					);
				}
			}
			return out;
		}
	}

	/** a record that knows its own identifier, who said so, and what else names it */
	private static class Scripted implements Record {
		private final RecordReference self;
		private final Provenance provenance;
		private final List<RecordReference> others;

		Scripted(
				RecordReference self, String source, List<RecordReference> others
		) {
			this.self = self;
			this.provenance = Provenance.placeholder(source);
			this.others = others;
		}

		@Override
		public Optional<String> getIdentifier() {
			return this.self.getIdentifier();
		}
		@Override
		public IdType getIdentifierType() { return this.self.getIdentifierType(); }
		@Override
		public List<RecordReference> getOtherIdentifiers() { return this.others; }
		@Override
		public List<Author> getAuthors() { return List.of(); }
		@Override
		public java.util.stream.Stream<String> getLicenses() {
			return java.util.stream.Stream.empty();
		}
		@Override
		public Optional<String> getAbstract() { return Optional.empty(); }
		@Override
		public Optional<String> getTitle() {
			return Optional.of("Trends in the use of the word trends");
		}
		@Override
		public Optional<String> getJournal() { return Optional.empty(); }
		@Override
		public Optional<java.time.LocalDate> getDate() { return Optional.empty(); }
		@Override
		public Optional<java.net.URI> getPdfUri() { return Optional.empty(); }
		@Override
		public Provenance getProvenance() { return this.provenance; }
	}

	private static java.nio.file.Path noApis() throws IOException {
		var empty = java.nio.file.Files
			.createTempFile("biblio-no-secrets", ".prop");
		empty.toFile()
			.deleteOnExit();
		return empty;
	}

}
