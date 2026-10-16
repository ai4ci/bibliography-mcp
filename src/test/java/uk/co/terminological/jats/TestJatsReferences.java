package uk.co.terminological.jats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.Test;

import uk.co.terminological.bibliography.jats.JatsEntry;
import uk.co.terminological.bibliography.jats.JatsReference;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.fluentxml.Xml;

/**
 * The reference list of a publisher's JATS, which is where a preprint's references come from. Measured against a real
 * medRxiv article: of 28 references, 14 carry a DOI, 18 an `article-title`, and **10 neither** — reports and web pages,
 * where HighWire puts the descriptive text in `collab`.
 *
 * [TESTS](/design/test-scripts/test-005-preprint-tests.md)
 */
public class TestJatsReferences {

	private static final String JATS = String.join(
		"\n",
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
		"<article article-type=\"article\">",
		"  <front><article-meta>",
		"    <title-group><article-title>A preprint title</article-title></title-group>",
		"  </article-meta></front>",
		"  <back><ref-list>",
		// a normal journal reference
		"    <ref id=\"c1\"><label>1</label><citation publication-type=\"journal\" citation-type=\"journal\">",
		"      <string-name><surname>Wise</surname> <given-names>J.</given-names></string-name>",
		"      <article-title>Covid-19: New coronavirus variant is identified in UK</article-title>",
		"      <source>BMJ</source> <year>2020</year><volume>371</volume><fpage>m4857</fpage>",
		"      doi:<pub-id pub-id-type=\"doi\">10.1136/bmj.m4857</pub-id>",
		"      <pub-id pub-id-type=\"pmid\">33328153</pub-id></citation></ref>",
		// grey literature: no article-title, the name is in collab
		"    <ref id=\"c2\"><label>2</label><citation publication-type=\"other\" citation-type=\"journal\">",
		"      <collab>Investigation of novel SARS-CoV-2 variant: technical briefing 2</collab>",
		"      <source>Public Health England</source> <year>2020</year></citation></ref>",
		// a web page with no year at all
		"    <ref id=\"c3\"><label>3</label><citation publication-type=\"website\" citation-type=\"web\">",
		"      <collab>Prime Minister announces Tier 4: Stay At Home</collab></citation></ref>",
		// a year that is not a number, which must not throw
		"    <ref id=\"c4\"><label>4</label><citation publication-type=\"journal\" citation-type=\"journal\">",
		"      <article-title>In press somewhere</article-title><source>Somewhere</source> <year>in press</year>",
		"      </citation></ref>",
		"  </ref-list></back>",
		"</article>"
	);

	private List<JatsReference> references() throws Exception {
		var entry = new JatsEntry(
				Xml.fromStream(
					new ByteArrayInputStream(JATS.getBytes(StandardCharsets.UTF_8))
				)
					.content(),
				Provenance.placeholder("test")
		);
		return entry.getReferences();
	}

	@Test
	public void aJournalReferenceGivesItsTitleAndDoi() throws Exception {
		var ref = this.references()
			.get(0);

		assertEquals(
			Optional.of("Covid-19: New coronavirus variant is identified in UK"),
			ref.getTitle()
		);
		assertEquals(Optional.of("10.1136/bmj.m4857"), ref.getIdentifier());
		assertEquals(Optional.of("BMJ"), ref.getJournal());
		assertEquals(Optional.of(LocalDate.of(2020, 1, 1)), ref.getDate());
		assertEquals(
			List.of("Wise"),
			ref.getAuthors()
				.stream()
				.map(Author::getLastName)
				.collect(Collectors.toList())
		);
	}

	@Test
	public void greyLiteratureIsNamedByItsCollabElement() throws Exception {
		// there is no article-title on this reference, and the old parser returned
		// nothing at all: it gated on `publication-type == "journal"`, comparing strings
		// by identity
		var ref = this.references()
			.get(1);

		assertEquals(
			Optional.of(
				"Investigation of novel SARS-CoV-2 variant: technical briefing 2"
			),
			ref.getTitle()
		);
		assertEquals(Optional.of("Public Health England"), ref.getJournal());
		assertTrue(
			ref.getIdentifier()
				.isEmpty()
		);
	}

	@Test
	public void aWebPageWithNoSourceIsStillNamed() throws Exception {
		assertEquals(
			Optional.of("Prime Minister announces Tier 4: Stay At Home"),
			this.references()
				.get(2)
				.getTitle()
		);
	}

	@Test
	public void anUnparseableYearIsAbsentRatherThanAnException()
			throws Exception {
		assertTrue(
			this.references()
				.get(3)
				.getDate()
				.isEmpty()
		);
		assertEquals(
			Optional.of("In press somewhere"),
			this.references()
				.get(3)
				.getTitle()
		);
	}

	@Test
	public void otherIdentifiersAreCarriedThrough() throws Exception {
		// a reference often has a PMID even when it has no DOI
		List<? extends RecordReference> others = this.references()
			.get(0)
			.getOtherIdentifiers();

		assertEquals(1, others.size());
		assertEquals(
			IdType.PMID,
			others.get(0)
				.getIdentifierType()
		);
		assertEquals(
			Optional.of("33328153"),
			others.get(0)
				.getIdentifier()
		);
	}

	@Test
	public void aReferenceWithNeitherIdentifierNorTitleIsStillAnIncompleteReference()
			throws Exception {
		// the article itself has a title, so this is about the reference: it must not
		// claim to be complete, but it must not be silently dropped either
		var entry = new JatsEntry(
				Xml.fromStream(
					new ByteArrayInputStream(JATS.getBytes(StandardCharsets.UTF_8))
				)
					.content(),
				Provenance.placeholder("test")
		);
		assertEquals(
			4,
			entry.getReferences()
				.size()
		);
		assertFalse(
			entry.getReferences()
				.get(1)
				.isComplete()
		);
	}
}
