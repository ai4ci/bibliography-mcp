package uk.co.terminological.bibliography.medrxiv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import uk.co.terminological.bibliography.jats.JatsEntry;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.PublicationTypes;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.fluentxml.Xml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * feat-009 option B: medRxiv records, and the references that only its JATS full
 * text can supply.
 *
 * Fixtures are sanitised copies of the real shapes (same fields, invented values).
 *
 * [TESTS](/design/test-scripts/test-005-preprint-tests.md)
 */
public class TestMedrxivRecords {

	private final ObjectMapper mapper = new ObjectMapper();

	private MedrxivRecord version(String json) throws Exception {
		return new MedrxivRecord(mapper.readTree(json), "medRxiv");
	}

	@Test
	public void aPreprintIsPostedContentContainedByItsServer() throws Exception {
		MedrxivRecord record = version("{\"doi\":\"10.1101/2021.01.01.12345678\","
				+ "\"title\":\"A preprint <i>title</i>\",\"abstract\":\"An abstract.\","
				+ "\"authors\":\"Challen, R.; Brooks-Pollock, E.; Danon, L.\",\"date\":\"2021-02-19\","
				+ "\"version\":\"2\",\"type\":\"PUBLISHAHEADOFPRINT\",\"license\":\"cc_by\","
				+ "\"category\":\"infectious diseases\",\"published\":\"10.1136/bmj.n579\","
				+ "\"jatsxml\":\"https://www.medrxiv.org/content/early/2021/02/19/x.source.xml\"}");

		assertEquals(IdType.DOI, record.getIdentifierType());
		assertEquals("10.1101/2021.01.01.12345678", record.getIdentifier().orElse(null));
		assertEquals("A preprint title", record.getTitle().orElse(null)); // markup cleaned
		assertEquals("An abstract.", record.getAbstract().orElse(null));
		assertEquals(PublicationTypes.POSTED_CONTENT, record.getPublicationType().orElse(null));
		// the container is the server, and there is no publisher to invent
		assertEquals("medRxiv", record.getJournal().orElse(null));
		assertTrue(record.getPublisher().isEmpty());
		assertEquals(LocalDate.of(2021, 2, 19), record.getDate().orElse(null));
		assertEquals("2", record.getVersion().orElse(null));
		assertEquals(List.of("cc_by"), record.getLicenses().collect(Collectors.toList()));
		// the published article is a different work, so it is not an identifier here
		assertTrue(record.getOtherIdentifiers().isEmpty());
		assertEquals("medrxiv", record.getProvenance().orElseThrow().getSource());
		assertEquals("PUBLISHAHEADOFPRINT", record.getProvenance().orElseThrow().getNativeType().orElse(null));
	}

	@Test
	public void authorsAreSplitOnTheServersSemicolonConvention() throws Exception {
		MedrxivRecord record = version("{\"doi\":\"10.1101/x\",\"authors\":\"H1 Collaboration; Challen, R.\"}");

		List<? extends Author> authors = record.getAuthors();
		assertEquals(2, authors.size());
		assertEquals("H1 Collaboration", authors.get(0).getLastName()); // an institution
		assertTrue(authors.get(0).getInitials().isEmpty());
		assertEquals("Challen", authors.get(1).getLastName());
		assertEquals("R.", authors.get(1).getInitials().orElse(null));
	}

	@Test
	public void onlyPreprintDoisAreWorthARequest() {
		assertTrue(MedrxivClient.isPreprintDoi("10.1101/2021.01.01.12345678"));
		assertTrue(MedrxivClient.isPreprintDoi(" 10.1101/x "));
		assertFalse(MedrxivClient.isPreprintDoi("10.1136/bmj.n579"));
		assertFalse(MedrxivClient.isPreprintDoi(null));
	}

	@Test
	public void thePublishedVersionMappingIsReadFromThePubsEndpoint() throws Exception {
		var mapping = MedrxivClient.mappingFromPubs(
				mapper.readTree("{\"collection\":[{\"preprint_doi\":\"10.1101/2021.01.01.12345678\","
						+ "\"published_doi\":\"10.1136/bmj.n579\",\"published_journal\":\"BMJ\"}]}"),
				"10.1101/2021.01.01.12345678").orElseThrow();

		assertEquals("10.1101/2021.01.01.12345678", mapping.getSource().getIdentifier().orElse(null));
		assertEquals("10.1136/bmj.n579", mapping.getTarget().getIdentifier().orElse(null));
	}

	@Test
	public void anUnpublishedPreprintHasNoMapping() throws Exception {
		assertTrue(MedrxivClient.mappingFromPubs(
				mapper.readTree("{\"collection\":[]}"), "10.1101/x").isEmpty());
		assertTrue(MedrxivClient.mappingFromPubs(
				mapper.readTree("{\"collection\":[{\"preprint_doi\":\"10.1101/x\"}]}"), "10.1101/x").isEmpty());
	}

	@Test
	public void aTermIsMatchedAgainstTitleAndAbstractBecauseTheApiCannot() throws Exception {
		MedrxivRecord record = version("{\"doi\":\"10.1101/x\",\"title\":\"Causal inference in practice\","
				+ "\"abstract\":\"A study of target trial emulation.\"}");

		// every word must appear, not the phrase as one substring: a phrase match made a
		// two-word query return nothing in a window holding 1498 preprints
		assertTrue(MedrxivClient.matches(record, "causal inference"));
		assertTrue(MedrxivClient.matches(record, "TRIAL EMULATION"));
		assertTrue(MedrxivClient.matches(record, "inference target"));
		assertFalse(MedrxivClient.matches(record, "causal genomics"));
		assertFalse(MedrxivClient.matches(record, "genomics"));
		// no term means the whole window
		assertTrue(MedrxivClient.matches(record, ""));
	}

	/** the reference list, which is the whole point of option B */
	private static final String JATS = String.join("\n",
			"<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
			"<article article-type=\"article\">",
			"  <front>",
			"    <article-meta>",
			"      <article-id pub-id-type=\"doi\">10.1101/2021.01.01.12345678</article-id>",
			"      <title-group><article-title>A preprint title</article-title></title-group>",
			"    </article-meta>",
			"  </front>",
			"  <body><p>Text.</p></body>",
			"  <back>",
			"    <ref-list>",
			"      <ref id=\"c1\"><label>1</label><citation publication-type=\"journal\">",
			"        <string-name><surname>Wise</surname> <given-names>J.</given-names></string-name>",
			"        <article-title>Covid-19: New coronavirus variant is identified in UK</article-title>",
			"        <source>BMJ</source> <year>2020</year><volume>371</volume><fpage>m4857</fpage>",
			"        doi:<pub-id pub-id-type=\"doi\">10.1136/bmj.m4857</pub-id></citation></ref>",
			"      <ref id=\"c2\"><label>2</label><citation publication-type=\"journal\">",
			"        <string-name><surname>Danon</surname> <given-names>L.</given-names></string-name>",
			"        <article-title>A paper without a doi</article-title>",
			"        <source>Nature</source> <year>2019</year></citation></ref>",
			"      <ref id=\"c3\"><label>3</label><citation publication-type=\"preprint\">",
			"        <article-title>Another preprint</article-title>",
			"        <pub-id pub-id-type=\"doi\">10.1101/2020.01.01.12345678</pub-id></citation></ref>",
			"    </ref-list>",
			"  </back>",
			"</article>");

	private JatsEntry article() throws Exception {
		return new JatsEntry(Xml.fromStream(new ByteArrayInputStream(JATS.getBytes(StandardCharsets.UTF_8))).content());
	}

	@Test
	public void theJatsFullTextYieldsTheReferenceList() throws Exception {
		JatsEntry entry = article();

		assertEquals(Optional.of("A preprint title"), entry.getTitle());
		assertEquals(3, entry.getReferences().size());
		assertEquals(Optional.of("10.1101/2021.01.01.12345678"), entry.getDoi());
	}

	@Test
	public void eachReferenceBecomesACitationLinkFromThePreprint() throws Exception {
		List<CitationLink> links = article().getCitations().collect(Collectors.toList());

		assertEquals(3, links.size());
		// the preprint is the source, and each reference is the target
		assertEquals("10.1101/2021.01.01.12345678", links.get(0).getSource().getIdentifier().get().getIdentifier().orElse(null));
		assertEquals("10.1136/bmj.m4857", doi(links.get(0)));
		assertEquals(Optional.of(1), links.get(0).getIndex());
		assertEquals("10.1101/2020.01.01.12345678", doi(links.get(2)));
		// a reference with no DOI is still a reference, just an incomplete one
		assertTrue(doi(links.get(1)) == null);
		assertFalse(links.get(1).isComplete());
	}

	private static String doi(CitationLink link) {
		return link.getTarget().getIdentifier()
				.map(RecordReference::getIdentifier)
				.orElse(Optional.empty())
				.orElse(null);
	}
}
