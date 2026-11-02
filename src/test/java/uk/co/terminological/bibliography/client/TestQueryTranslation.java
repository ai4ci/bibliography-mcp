package uk.co.terminological.bibliography.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import uk.co.terminological.bibliography.api.crossref.CrossRefClient;
import uk.co.terminological.bibliography.api.entrez.EntrezClient;
import uk.co.terminological.bibliography.api.europepmc.EuropePMCClient;
import uk.co.terminological.bibliography.api.openalex.OpenAlexClient;

/**
 * Every API gets the same {@link Query} and has to turn it into its own syntax. These assert that translation against
 * each API's published spec, without a network call: the request is built and inspected, never sent.
 *
 * Written against what the {@link Query} contract says each field means, not against what the builders currently emit.
 * In particular {@code getAfter()}/{@code getUntil()} are a **publication** date window — a year is a publication year
 * — so an API's publication-date filter is the correct target and its deposit/index-date filter is not.
 *
 * The live counterpart is {@code TestQueryTranslationLive}, which checks that the APIs agree.
 *
 * [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
 */
public class TestQueryTranslation {

	// a deliberately complete query: every structured field populated at once, so
	// that a field being dropped shows up rather than being masked by the others
	private static Query everything() {
		return ImmutableQuery.builder()
			.allFields("sars-cov-2")
			.titleAbstract("transmission")
			.addFullText("household")
			.addAuthors("Challen")
			.journal("BMJ")
			.year(2020)
			.limit(17)
			.build();
	}

	private static Query dateless(String term) {
		return ImmutableQuery.builder()
			.allFields(term)
			.build();
	}

	private static CrossRefClient crossref() {
		return CrossRefClient.create("test@example.com");
	}

	private static EntrezClient entrez() {
		return EntrezClient.create("test-key", "test-app", "test@example.com");
	}

	private static EuropePMCClient europepmc() {
		return EuropePMCClient.create("test@example.com");
	}

	// ---- CrossRef -----------------------------------------------------------

	@Test
	public void crossrefWithQueryReturnsTheBuilderSoItCanBeExecuted() {
		// SearchBuilder is a fluent interface and CrossRefClient.search() relies on
		// it: `buildQuery().withQuery(q).execute()` has to work
		var builder = crossref().buildQuery();

		assertNotNull(
			"withQuery must return the builder, not null",
			builder.withQuery(everything())
		);
	}

	@Test
	public void crossrefFiltersOnDepositDateByDesign() {
		// Pinning a deliberate choice rather than the obvious one. from-pub-date
		// exists and matches what getAfter()/getUntil() mean, but Crossref's
		// publication dates are inconsistently populated across members, so the
		// deposit date is the more reliable proxy here (CrossRefClient.since).
		// If that trade-off is ever revisited this test should be the thing that
		// notices.
		var builder = crossref().buildQuery();
		builder.withQuery(everything());

		var filters = builder.params()
			.get("filter");

		assertTrue(
			"expected the deposit-date filter, got " + filters,
			filters.contains("from-created-date:2020-01-01")
		);
		assertTrue(
			"expected the deposit-date filter, got " + filters,
			filters.contains("until-created-date:2020-12-31")
		);
	}

	@Test
	public void crossrefSendsTheTitleTermAsAFieldQuery() {
		// query.title is a supported field query: the API's own validation lists it,
		// and it is in the swagger for /works
		var builder = crossref().buildQuery();
		builder.withQuery(everything());

		assertEquals(
			"transmission",
			builder.params()
				.getFirst("query.title")
		);
	}

	@Test
	public void crossrefFieldNamesAreSpeltAsCrossrefSpellsThem() {
		// Crossref rejects an unknown field query outright with
		// "field-query-not-available", so a misspelt enum value does not degrade the
		// search -- it fails the whole request. The live counterpart proves the list
		// against the API; this one keeps the spelling honest offline.
		var valid = List.of(
			"title",
			"container-title",
			"author",
			"editor",
			"chair",
			"translator",
			"contributor",
			"bibliographic",
			"affiliation"
		);

		for (CrossRefClient.Field field : CrossRefClient.Field.values()) {
			var builder = crossref().buildQuery();
			builder.withSearchTerm(field, "x");
			var rendered = builder.params()
				.keySet()
				.stream()
				.filter(k -> k.startsWith("query."))
				.findFirst()
				.orElseThrow()
				.substring("query.".length());

			assertTrue(
				field + " renders as query." + rendered
						+ ", which Crossref does not accept",
				valid.contains(rendered)
			);
		}
	}

	@Test
	public void crossrefMapsAuthorsAndJournalToTheirFieldQueries() {
		var builder = crossref().buildQuery();
		builder.withQuery(everything());

		var params = builder.params();

		assertEquals("Challen", params.getFirst("query.author"));
		assertEquals("BMJ", params.getFirst("query.container-title"));
	}

	@Test
	public void crossrefPassesALimitThroughAsRows() {
		var builder = crossref().buildQuery();
		builder.withQuery(everything());

		assertEquals(
			"17",
			builder.params()
				.getFirst("rows")
		);
	}

	@Test
	public void crossrefPassesANativeQueryThrough() {
		// every other client honours getNativeQuery(); a caller who asks for their
		// own syntax and silently gets an unfiltered search is worse served than one
		// who gets an error
		var builder = crossref().buildQuery();
		builder.withQuery(
			ImmutableQuery.builder()
				.nativeQuery("hubble")
				.build()
		);

		assertTrue(
			"the native query was dropped: " + builder.params(),
			builder.params()
				.toString()
				.contains("hubble")
		);
	}

	// ---- Entrez -------------------------------------------------------------

	@Test
	public void entrezFiltersOnPublicationDateNotEntrezDate() {
		// edat is the date the citation was added to PubMed, which for an older
		// work bears no relation to when it was published
		var builder = entrez().buildQuery();
		builder.withQuery(everything());

		var params = builder.params();

		assertEquals(
			"a publication-year window is a pdat window",
			"pdat",
			params.getFirst("datetype")
		);
		assertEquals("2020/01/01", params.getFirst("mindate"));
		assertEquals("2020/12/31", params.getFirst("maxdate"));
	}

	@Test
	public void entrezAlwaysSendsBothEndsOfADateRange() {
		// "You must include both a mindate and a maxdate to specify a date range"
		// (E-utilities spec) -- a one-sided bound is ignored outright, so an
		// open-ended "since" has to be closed at today rather than left dangling
		var builder = entrez().buildQuery();
		builder.withQuery(
			ImmutableQuery.builder()
				.allFields("malaria")
				.after(LocalDate.of(2015, 3, 1))
				.build()
		);

		var params = builder.params();

		assertEquals("2015/03/01", params.getFirst("mindate"));
		assertNotNull(
			"a mindate without a maxdate is ignored by eSearch",
			params.getFirst("maxdate")
		);
	}

	@Test
	public void entrezTagsEachTermWithItsPubmedFieldAndJoinsWithAnd() {
		var builder = entrez().buildQuery();
		builder.withQuery(everything());

		var term = builder.term();

		assertTrue(term, term.contains("(transmission[Title/Abstract])"));
		assertTrue(term, term.contains("(Challen[Author])"));
		assertTrue(term, term.contains("(BMJ[Journal])"));
		assertTrue(term, term.contains("(household[Text Word])"));
		assertTrue(term, term.contains("(sars-cov-2)"));
		assertTrue("terms are ANDed together: " + term, term.contains(" AND "));
	}

	@Test
	public void entrezPassesALimitThroughAsRetmax() {
		var builder = entrez().buildQuery();
		builder.withQuery(everything());

		assertEquals(
			"17",
			builder.params()
				.getFirst("retmax")
		);
	}

	// ---- Europe PMC ---------------------------------------------------------

	@Test
	public void europePmcBuildsABalancedQuery() {
		// an unbalanced bracket is not rejected by the Europe PMC parser: it
		// silently changes what the query means
		var builder = europepmc().buildQuery();
		builder.withQuery(everything());

		var query = builder.query();

		assertEquals(
			"unbalanced parentheses in: " + query,
			query.chars()
				.filter(c -> c == '(')
				.count(),
			query.chars()
				.filter(c -> c == ')')
				.count()
		);
	}

	@Test
	public void europePmcFiltersOnFirstPublicationDate() {
		var builder = europepmc().buildQuery();
		builder.withQuery(everything());

		var query = builder.query();

		assertTrue(
			query,
			query.contains("FIRST_PDATE:[2020-01-01 TO 2020-12-31]")
		);
	}

	@Test
	public void europePmcLeavesAnUnaskedDateWindowAlone() {
		// a query with no date constraint should not acquire one: a manufactured
		// [1900 TO today] window silently drops anything the index dates outside it
		var builder = europepmc().buildQuery();
		builder.withQuery(dateless("malaria"));

		var query = builder.query();

		assertFalse(
			"a date filter was invented for an undated query: " + query,
			query.contains("FIRST_PDATE")
		);
	}

	@Test
	public void europePmcSearchesAJournalAsFreeTextByDesign() {
		// Pinning a deliberate choice. JOURNAL is a real Europe PMC field, but it
		// matches only the exact short title, so "BMJ" or "Lancet" as a user would
		// type it finds nothing. A free-text term is the lossier but usable option
		// (EuropePMCClient.SearchFields.JOURNAL). The cost is that the journal name
		// also matches papers that merely mention it, so this is a recall-over-
		// precision trade rather than a restriction.
		var builder = europepmc().buildQuery();
		builder.withQuery(everything());

		var query = builder.query();

		assertTrue(
			"the journal term is missing from: " + query,
			query.contains("(BMJ)")
		);
		assertFalse(
			"an exact-match JOURNAL restriction would find nothing for a colloquial name: "
					+ query,
			query.contains("JOURNAL:")
		);
	}

	@Test
	public void europePmcTagsTitleAbstractAndAuthorTerms() {
		var builder = europepmc().buildQuery();
		builder.withQuery(everything());

		var query = builder.query();

		assertTrue(query, query.contains("TITLE_ABS:(transmission)"));
		assertTrue(query, query.contains("AUTH:(Challen)"));
	}

	@Test
	public void europePmcKeepsPageSizeWithinTheDocumentedMaximum() {
		// pageSize caps at 1000; a larger value is a request the API will reject
		var builder = europepmc().buildQuery();
		builder.withQuery(
			ImmutableQuery.builder()
				.allFields("malaria")
				.limit(5000)
				.build()
		);

		var pageSize = Integer.parseInt(
			builder.params()
				.getFirst("pageSize")
		);

		assertTrue("pageSize was " + pageSize, pageSize <= 1000);
	}

	// ---- medRxiv / bioRxiv --------------------------------------------------

	// Medrxiv search not possible due to cloudflare
//	@Test
//	public void medrxivIncludesTheAuthorsItWasGiven() {
//		var builder = MedrxivClient.create()
//			.buildQuery();
//		builder.withQuery(everything());
//
//		var query = builder.query();
//
//		assertTrue("the author term was dropped: " + query, query.contains("Challen"));
//	}
//
//	@Test
//	public void medrxivUsesEachTermOnce() {
//		// the same term sent as two different fields doubles the request and makes
//		// the match stricter than asked for
//		var builder = MedrxivClient.create()
//			.buildQuery();
//		builder.withQuery(dateless("sars-cov-2"));
//
//		var query = builder.query();
//		var occurrences = query.split("sars-cov-2", -1).length - 1;
//
//		assertEquals("term repeated in: " + query, 1, occurrences);
//	}
//
//	@Test
//	public void medrxivEveryTermIsAFieldValuePair() {
//		// the query goes into the request path as space separated field:value
//		// pairs; a bare value is not a search term, it is a malformed path segment
//		var builder = MedrxivClient.create()
//			.buildQuery();
//		builder.withQuery(dateless("sars-cov-2"));
//
//		for (String term : builder.query()
//			.split(" ")) {
//			assertTrue(
//				"not a field:value pair: '" + term + "' in " + builder.query(),
//				term.contains(":")
//			);
//		}
//	}
//
//	@Test
//	public void medrxivPassesTheDateWindowAndLimitThrough() {
//		var builder = MedrxivClient.create()
//			.buildQuery();
//		builder.withQuery(everything());
//
//		var query = builder.query();
//
//		assertTrue(query, query.contains("limit_from:2020-01-01"));
//		assertTrue(query, query.contains("limit_to:2020-12-31"));
//		assertTrue(query, query.contains("numresults:17"));
//	}

	// ---- OpenAlex -----------------------------------------------------------

	private static OpenAlexClient openalex() {
		return OpenAlexClient.create(null, "test@example.com");
	}

	@Test
	public void openAlexCombinesEveryTermInOneFilterParameter() {
		// repeating `filter=` does not AND: measured, a second filter parameter is
		// ignored outright and the hit count runs to the millions. Conditions have to
		// be comma-joined inside a single parameter or the query silently stops meaning
		// what was asked
		var builder = openalex().buildQuery();
		builder.withQuery(everything());

		var filter = builder.filter();

		assertFalse(
			"a second filter parameter would be ignored by the API: " + filter,
			filter.contains("filter=")
		);
		assertTrue(filter, filter.contains("default.search:sars-cov-2"));
		assertTrue(filter, filter.contains("title_and_abstract.search:transmission"));
		assertTrue(filter, filter.contains("raw_author_name.search:Challen"));
		assertTrue(
			"the journal term was dropped: " + filter,
			builder.journalName()
				.isPresent()
		);
	}

	@Test
	public void openAlexFiltersOnPublicationDate() {
		var builder = openalex().buildQuery();
		builder.withQuery(everything());

		var filter = builder.filter();

		assertTrue(filter, filter.contains("from_publication_date:2020-01-01"));
		assertTrue(filter, filter.contains("to_publication_date:2020-12-31"));
	}

	@Test
	public void openAlexSendsAOneSidedDateWindowAsAsked() {
		// Entrez needs both ends or it ignores the range; OpenAlex does not, so closing
		// an open-ended "since" at today would invent a bound the caller never set
		var builder = openalex().buildQuery();
		builder.withQuery(
			ImmutableQuery.builder()
				.allFields("malaria")
				.after(LocalDate.of(2015, 3, 1))
				.build()
		);

		var filter = builder.filter();

		assertTrue(filter, filter.contains("from_publication_date:2015-03-01"));
		assertFalse(
			"a bound was invented for an open-ended window: " + filter,
			filter.contains("to_publication_date")
		);
	}

	@Test
	public void openAlexLeavesAnUnaskedDateWindowAlone() {
		var builder = openalex().buildQuery();
		builder.withQuery(dateless("malaria"));

		assertFalse(
			"a date filter was invented for an undated query: " + builder.filter(),
			builder.filter()
				.contains("publication_date")
		);
	}

	@Test
	public void openAlexChargesATextSearchOnceNotOncePerFilter() {
		// measured against the live API: an ISSN filter ANDed with a text search costs
		// 10 credits, not 11, and two text filters together cost 10, not 20. A request
		// is priced by its dearest condition, so summing would over-report spend against
		// a daily budget the crawl is meant to stop on
		var builder = openalex().buildQuery();
		builder.titleAndAbstract("malaria");
		builder.journal("1471-2334");
		builder.between(
				Optional.of(LocalDate.of(2020, 1, 1)),
				Optional.of(LocalDate.of(2020, 12, 31))
		);

		assertEquals(
			"a request costs the most expensive thing it asks for",
			10,
			builder.cost()
		);
	}

	@Test
	public void openAlexPassesAnIssnThroughAsAnExactRestriction() {
		// OpenAlex cannot filter works by container name, only by ISSN. Given an ISSN the
		// client needs no lookup, so this must appear in the filter rather than waiting
		// behind journalName() like a name does
		var builder = openalex().buildQuery();
		builder.journal("1471-2334");

		assertTrue(
			builder.filter(),
			builder.filter()
				.contains("primary_location.source.issn:1471-2334")
		);
	}

	@Test
	public void openAlexPassesALimitThroughAsThePageSize() {
		var builder = openalex().buildQuery();
		builder.withQuery(everything());

		assertEquals(17, builder.perPage());
	}

	@Test
	public void openAlexKeepsThePageSizeWithinTheDocumentedMaximum() {
		// per-page above 200 is a 400 ("Pagination error"), so an oversized limit has to
		// be clamped rather than sent
		var builder = openalex().buildQuery();
		builder.withQuery(
			ImmutableQuery.builder()
				.allFields("malaria")
				.limit(5000)
				.build()
		);

		assertTrue("per-page was " + builder.perPage(), builder.perPage() <= 200);
	}

	@Test
	public void openAlexPassesANativeQueryThrough() {
		var builder = openalex().buildQuery();
		builder.withQuery(
			ImmutableQuery.builder()
				.nativeQuery("cites:W3134566701")
				.build()
		);

		assertEquals("cites:W3134566701", builder.filter());
	}

	@Test
	public void openAlexAsksForNothingWhenNothingWasAsked() {
		// an empty filter is not "match nothing" to this API, it is an unfiltered
		// request over the whole corpus: 1 credit for every work there is
		assertTrue(
			"an empty query would be sent as an unfiltered request",
			openalex().buildQuery()
				.execute()
				.isEmpty()
		);
	}

	// ---- the shared contract ------------------------------------------------

	@Test
	public void aYearIsTheWholeOfThatYear() {
		var qry = ImmutableQuery.builder()
			.allFields("x")
			.year(2020)
			.build();

		assertEquals(
			LocalDate.of(2020, 1, 1),
			qry.getAfter()
				.orElse(null)
		);
		assertEquals(
			LocalDate.of(2020, 12, 31),
			qry.getUntil()
				.orElse(null)
		);
	}

	@Test
	public void anExplicitWindowOverridesTheYearShorthand() {
		var qry = ImmutableQuery.builder()
			.allFields("x")
			.year(2020)
			.after(LocalDate.of(2020, 6, 1))
			.until(LocalDate.of(2020, 6, 30))
			.build();

		assertEquals(
			LocalDate.of(2020, 6, 1),
			qry.getAfter()
				.orElse(null)
		);
		assertEquals(
			LocalDate.of(2020, 6, 30),
			qry.getUntil()
				.orElse(null)
		);
	}

	@Test
	public void everyClientAcceptsTheSameQueryWithoutThrowing() {
		// one Query is meant to be routable to all of them; BiblioFacade.multiSearch
		// has no per-API error handling, so one client throwing loses every result
		var qry = everything();
		List<Runnable> translations = List.of(
			() -> crossref().buildQuery()
				.withQuery(qry),
			() -> entrez().buildQuery()
				.withQuery(qry),
			() -> europepmc().buildQuery()
				.withQuery(qry),
			() -> openalex().buildQuery()
				.withQuery(qry)
		);

		for (Runnable translation : translations) {
			translation.run();
		}
	}
}
