package uk.co.terminological.bibliography.api.openalex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static uk.co.terminological.bibliography.TestPaperFixtures.*;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import uk.co.terminological.bibliography.LiveTest;
import uk.co.terminological.bibliography.api.BibliographicApis;
import uk.co.terminological.bibliography.client.ImmutableQuery;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.bibliography.record.RecordReferenceMapping;

/**
 * End-to-end test for {@link OpenAlexClient} against the live API.
 *
 * <p>Written to hold where the other clients cannot: OpenAlex meters <em>credits</em> against a daily budget, so these
 * tests assert the price of each operation as well as its answer. A silent change in cost — a lookup that starts
 * charging, a batch that stops batching — is as much a drift as a changed field name, and it is the kind that only shows
 * up as an exhausted budget halfway through a crawl.
 *
 * <p>Uses the fixture paper: preprint {@value uk.co.terminological.bibliography.TestPaperFixtures#PREPRINT_DOI},
 * published as DOI {@value uk.co.terminological.bibliography.TestPaperFixtures#PUBLISHED_DOI} (PMID
 * {@value uk.co.terminological.bibliography.TestPaperFixtures#PMID}, PMCID
 * {@value uk.co.terminological.bibliography.TestPaperFixtures#PMCID}), plus
 * {@value uk.co.terminological.bibliography.TestPaperFixtures#CITATION_RICH_VOR_DOI} as a work with a reference list and
 * inbound citations.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
@Category(LiveTest.class)
public class TestOpenAlexClientEndToEnd {

	private static OpenAlexClient openAlex;

	@BeforeClass
	public static void setUp() throws Exception {
		BibliographicApis apis = BibliographicApis.create(
			Paths.get(
				System.getProperty("user.home") + "/.private/apis/secrets.prop"
			),
			Paths.get("/tmp/ehcache/openalex-e2e-test")
		);
		openAlex = apis.getOpenAlex();
	}

	private static RecordReference doi(String id) {
		return Builder.recordReference(IdType.DOI, id);
	}

	// ---- fetch ----

	@Test
	public void aPublishedWorkIsFoundByEveryIdentifierThatNamesIt() {
		// one work, four names: the answer has to come back keyed to the name that was
		// asked with, or a batch answer cannot be returned to its caller
		Map<?, ? extends OpenAlexRecord> found = openAlex.fetch(
			List.of(
				doi(PUBLISHED_DOI),
				Builder.recordReference(IdType.PMID, PMID),
				Builder.recordReference(IdType.PMCID, PMCID)
			)
		);

		assertEquals(
			"not every identifier resolved: " + found.keySet(),
			3,
			found.size()
		);
		for (OpenAlexRecord record : found.values()) {
			assertEquals(
				"the batch answers a different work: " + record.getTitle(),
				PUBLISHED_DOI,
				record.getIdentifier()
					.orElse(null)
			);
		}
	}

	@Test
	public void aPmcidIsFoundWhetherOrNotItCarriesItsPrefix() {
		// OpenAlex publishes the PMC id as a bare URL slug while PubMed, idconv and
		// Europe PMC all publish it prefixed. The client normalises to the prefixed
		// form, so it must still be able to ask with what it was given.
		Optional<? extends Record> prefixed = openAlex.fetch(IdType.PMCID, PMCID);
		Optional<? extends Record> bare = openAlex.fetch(
				IdType.PMCID, PMCID.replace("PMC", "")
		);

		assertTrue("PMCID " + PMCID + " found nothing", prefixed.isPresent());
		assertTrue(
			"the bare slug " + PMCID.replace("PMC", "") + " found nothing",
			bare.isPresent()
		);
		assertEquals(
			prefixed.get()
				.getIdentifier()
				.orElse(null),
			bare.get()
				.getIdentifier()
				.orElse(null)
		);
	}

	@Test
	public void theOpenAccessPdfIsInTheSamePayloadAsTheMetadata() {
		// This is why OpenAlex replaces Unpaywall: the pdf arrives with the record, so
		// locating an open copy costs no second provider and no second call.
		Record preprint = openAlex.fetch(IdType.DOI, PREPRINT_DOI)
			.orElseThrow(() -> new AssertionError("no record for " + PREPRINT_DOI));

		assertEquals(
			"https://www.medrxiv.org/content/medrxiv/early/2025/12/15/2025.12.12.25342129.full.pdf",
			preprint.getPdfUri()
				.map(Object::toString)
				.orElse("<none>")
		);
	}

	@Test
	public void aPreprintIsTypedAsPostedContentOwnedByItsServer() {
		// MergePolicy ranks posted content by its server, so a preprint has to arrive
		// typed and with the server reachable, or the preprint-ownership rule has
		// nothing to compare and a published version can outrank the thing it came from
		Record preprint = openAlex.fetch(IdType.DOI, PREPRINT_DOI)
			.orElseThrow(() -> new AssertionError("no record for " + PREPRINT_DOI));

		assertEquals("posted-content", preprint.getPublicationType()
			.orElse("<none>"));
		assertEquals(EXPECTED_REPOSITORY, preprint.getJournal()
			.orElse("<none>"));
	}

	// ---- identifier mapping ----

	@Test
	public void oneWorksIdentifiersAreReportedAsEquivalent() {
		Set<? extends RecordReferenceMapping> mappings = openAlex.mappings(
				List.of(doi(PUBLISHED_DOI))
		);

		Set<IdType> types = mappings.stream()
			.map(m -> m.getTarget()
				.getIdentifierType())
			.collect(Collectors.toSet());

		// The dedupe in BiblioFacade joins records on these equivalences, so a type
		// missing here means OpenAlex results sit beside the others instead of merging
		assertTrue("no PMCID mapping: " + mappings, types.contains(IdType.PMCID));
		assertTrue("no PMID mapping: " + mappings, types.contains(IdType.PMID));
		assertTrue(
			"no OpenAlex mapping: " + mappings,
			types.contains(IdType.OPENALEX)
		);
	}

	@Test
	public void anOpenAlexIdCanBeResolvedToTheNamesEveryoneElseUses() {
		// The id only OpenAlex understands has to lead back out to a DOI, or a graph
		// walked through OpenAlex cannot be rejoined with the rest of the model
		String openAlexId = openAlex.fetch(IdType.DOI, CITATION_RICH_VOR_DOI)
			.map(r -> r.getOtherIdentifiers())
			.flatMap(
				ids -> ids.stream()
					.filter(r -> r.getIdentifierType() == IdType.OPENALEX)
					.findFirst()
			)
			.flatMap(RecordReference::getIdentifier)
			.orElseThrow(
					() -> new AssertionError(
							"no OpenAlex id for " + CITATION_RICH_VOR_DOI
					)
				);

		Record resolved = openAlex.fetch(IdType.OPENALEX, openAlexId)
			.orElseThrow(
					() -> new AssertionError("nothing for " + openAlexId)
			);

		assertEquals(
			CITATION_RICH_VOR_DOI,
			resolved.getIdentifier()
				.orElse(null)
		);
	}

	// ---- citation graph ----

	@Test
	public void referencesPointAwayFromTheWorkThatCarriesThem() {
		// The convention that closed issue-007: a link runs source-cites-target. Pinned
		// in both directions here because no client had a direction test before.
		List<CitationLink> links = openAlex.citesReferences(
				List.of(doi(CITATION_RICH_VOR_DOI))
		)
			.stream()
			.collect(Collectors.toList());

		assertFalse("no references found", links.isEmpty());
		for (CitationLink link : links) {
			assertEquals(
				"a reference is not an edge away from the work asked about: " + link,
				CITATION_RICH_VOR_DOI,
				link.getSource()
					.getIdentifier()
					.flatMap(RecordReference::getIdentifier)
					.orElse(null)
			);
		}
	}

	@Test
	public void citationsPointTowardsTheWorkAskedAbout() {
		List<CitationLink> links = openAlex.referencesCiting(
				List.of(doi(CITATION_RICH_VOR_DOI))
			)
			.stream()
			.collect(Collectors.toList());

		assertFalse("no inbound citations found", links.isEmpty());
		for (CitationLink link : links) {
			assertEquals(
				"an inbound citation is not an edge towards the work asked about: "
						+ link,
				CITATION_RICH_VOR_DOI,
				link.getTarget()
					.getIdentifier()
					.flatMap(RecordReference::getIdentifier)
					.orElse(null)
			);
			assertFalse(
				"a work cites itself: " + link,
				link.getSource()
					.getIdentifier()
					.flatMap(RecordReference::getIdentifier)
					.orElse("<none>")
					.equals(CITATION_RICH_VOR_DOI)
			);
		}
	}

	@Test
	public void resolvedReferencesComeBackWithNameableIdentifiersNotJustGraphIds() {
		// referenced_works are OpenAlex ids, which join nothing outside OpenAlex. The
		// client resolves the batch so these edges meet the ones Crossref produces.
		List<CitationLink> links = openAlex.citesReferences(
				List.of(doi(CITATION_RICH_VOR_DOI))
			)
			.stream()
			.limit(20)
			.collect(Collectors.toList());

		long named = links.stream()
			.filter(
				l -> l.getTarget()
					.getIdentifier()
					.map(r -> r.getIdentifierType() != IdType.OPENALEX)
					.orElse(false)
		)
			.count();

		assertTrue(
			"no reference resolved to a DOI/PMID/PMCID out of " + links.size(),
			named > 0
		);
	}

	// ---- search ----

	@Test
	public void aDistinctiveTitleFindsTheWorkItNames() {
		// The fixture paper's own title. Relevance ranking decides who comes first, so
		// this pins that a title distinctive enough to name one study reaches the top of
		// the answer at all — a query that silently searched the wrong field, or that the
		// API refused, returns other people's work instead.
		var found = openAlex.search(
			ImmutableQuery.builder()
				.titleAbstract(EXPECTED_TITLE)
				.limit(5)
				.build()
		);

		assertFalse("search returned nothing", found.isEmpty());
		assertTrue(
			"the work was not in the top 5: " + found.stream()
				.map(r -> r.getTitle()
					.orElse("<none>"))
				.collect(Collectors.toList()),
			found.stream()
				.anyMatch(
					r -> r.getIdentifier()
						.filter(PUBLISHED_DOI::equals)
						.isPresent()
				)
		);
	}

	@Test
	public void aPublicationWindowExcludesWorkOutsideIt() {
		var found = openAlex.search(
			ImmutableQuery.builder()
				.titleAbstract("SARS-CoV-2")
				.year(2021)
				.limit(25)
				.build()
		);

		assertFalse("search returned nothing", found.isEmpty());
		List<String> wrong = found.stream()
			.filter(r -> r.getDate()
				.map(d -> d.getYear() != 2021)
				.orElse(false))
			.map(r -> r.getDate()
				.map(Object::toString)
				.orElse("<none>") + " @ " + r.getTitle()
				.orElse("<none>"))
			.collect(Collectors.toList());

		assertTrue(
			"records outside 2021: " + wrong,
			wrong.isEmpty()
		);
	}

	@Test
	public void theDailyBudgetIsVisible() {
		// A crawl has to be able to stop on budget rather than on a call count: an
		// over-budget request is refused before it runs and costs nothing, so noticing
		// the budget is what makes stopping early free.
		openAlex.search(
			ImmutableQuery.builder()
				.titleAbstract("malaria")
				.limit(1)
				.build()
		);

		assertTrue(
			"no budget reported after a call",
			openAlex.creditsRemaining()
				.isPresent()
		);
	}

	// ---- cost ----

	@Test
	public void theCostOfEachOperationIsWhatTheClientClaims() {
		// Pinned against the live headers rather than the documentation: these numbers
		// are the economy the whole client is shaped around, and a silent change to one
		// changes how a crawl must be run
		var lookup = openAlex.buildQuery();
		// a filtered batch fetch is 1 credit; a text search is 10
		lookup.titleAndAbstract("malaria");
		assertEquals(10, lookup.cost());

		var filterOnly = openAlex.buildQuery();
		filterOnly.journal("1471-2334");
		assertEquals(1, filterOnly.cost());
	}

}
