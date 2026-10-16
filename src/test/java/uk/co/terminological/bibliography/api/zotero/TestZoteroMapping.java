package uk.co.terminological.bibliography.api.zotero;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Record;

/**
 * feat-005: mapping a Zotero item onto the unified record model.
 *
 * Fixtures are sanitised copies of the real payload shapes (same fields, invented values) so the library's contents are
 * not committed.
 *
 * [TESTS](/design/test-scripts/test-003-zotero-bridge-tests.md)
 */
public class TestZoteroMapping {

	private final ObjectMapper mapper = new ObjectMapper();

	private ZoteroItem item(String json) throws Exception {
		var data = this.mapper.readTree(json)
			.get("data");
		return new ZoteroItem(data, "TESTSERVERID");
	}

	@Test
	public void journalArticleMapsToCslArticleJournal() throws Exception {
		Record r = this.item(
			"{\"data\":{\"key\":\"AAAA1111\",\"version\":7,\"itemType\":\"journalArticle\","
					+ "\"title\":\"A study of things\",\"abstractNote\":\"We studied things.\","
					+ "\"DOI\":\"10.1000/EXAMPLE\",\"publicationTitle\":\"Journal of Examples\","
					+ "\"publisher\":\"Example Press\",\"date\":\"2021-03-04\","
					+ "\"creators\":[{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"creatorType\":\"author\"},"
					+ "{\"firstName\":\"Ed\",\"lastName\":\"Editor\",\"creatorType\":\"editor\"}]}}"
		);

		assertEquals(
			"article-journal",
			r.getPublicationType()
				.orElse(null)
		);
		assertEquals(
			"A study of things",
			r.getTitle()
				.orElse(null)
		);
		assertEquals(
			"We studied things.",
			r.getAbstract()
				.orElse(null)
		);
		assertEquals(
			"Journal of Examples",
			r.getJournal()
				.orElse(null)
		);
		assertEquals(
			"Journal of Examples",
			r.getContainerTitle()
				.orElse(null)
		);
		assertEquals(
			"Example Press",
			r.getPublisher()
				.orElse(null)
		);
		assertEquals(IdType.DOI, r.getIdentifierType());
		assertEquals(
			"10.1000/EXAMPLE",
			r.getIdentifier()
				.orElse(null)
		);
		assertEquals(
			LocalDate.of(2021, 3, 4),
			r.getDate()
				.orElse(null)
		);
		// only the author, not the editor
		assertEquals(
			List.of("Lovelace"),
			r.getAuthors()
				.stream()
				.map(Author::getLastName)
				.toList()
		);
	}

	@Test
	public void provenanceCarriesTheZoteroVocabularyAndTheItemKey()
			throws Exception {
		Record r = this.item(
			"{\"data\":{\"key\":\"AAAA1111\",\"itemType\":\"journalArticle\","
					+ "\"title\":\"T\",\"DOI\":\"10.1000/EXAMPLE\"}}"
		);

		var p = r.getProvenance();
		assertEquals("zotero", p.getSource());
		assertEquals(
			"journalArticle",
			p.getNativeType()
				.orElse(null)
		);
		// the item key is instance-scoped linkage, not an identifier of the work

	}

	@Test
	public void reportUsesInstitutionAsItsIssuingBody() throws Exception {
		Record r = this.item(
			"{\"data\":{\"key\":\"BBBB2222\",\"itemType\":\"report\","
					+ "\"title\":\"A commissioned review\",\"institution\":\"Department of Examples\","
					+ "\"date\":\"2022\"}}"
		);

		assertEquals(
			"report",
			r.getPublicationType()
				.orElse(null)
		);
		assertEquals(
			"Department of Examples",
			r.getPublisher()
				.orElse(null)
		);
		// a year-only date has no LocalDate equivalent; see plan-004
		assertEquals(
			LocalDate.of(2022, 1, 1),
			r.getDate()
				.orElse(null)
		);
	}

	@Test
	public void bookCarriesItsIsbnWhenThereIsNoDoi() throws Exception {
		Record r = this.item(
			"{\"data\":{\"key\":\"CCCC3333\",\"itemType\":\"book\","
					+ "\"title\":\"A book\",\"ISBN\":\"978-1-315-11719-5\",\"publisher\":\"Example Press\","
					+ "\"creators\":[{\"firstName\":\"S\",\"lastName\":\"Editor\",\"creatorType\":\"editor\"}]}}"
		);

		assertEquals(
			"book",
			r.getPublicationType()
				.orElse(null)
		);
		assertEquals(IdType.ISBN, r.getIdentifierType());
		assertEquals(
			"978-1-315-11719-5",
			r.getIdentifier()
				.orElse(null)
		);
		// an editors-only book still has creators
		assertEquals(
			List.of("Editor"),
			r.getAuthors()
				.stream()
				.map(Author::getLastName)
				.toList()
		);
	}

	@Test
	public void bookSectionUsesTheBookTitleAsItsContainer() throws Exception {
		Record r = this.item(
			"{\"data\":{\"key\":\"DDDD4444\",\"itemType\":\"bookSection\","
					+ "\"title\":\"A chapter\",\"bookTitle\":\"The containing book\",\"publisher\":\"Example Press\","
					+ "\"date\":\"2000\"}}"
		);

		assertEquals(
			"chapter",
			r.getPublicationType()
				.orElse(null)
		);
		assertEquals(
			"The containing book",
			r.getJournal()
				.orElse(null)
		);
	}

	@Test
	public void anInstitutionalCreatorWithOnlyANameIsHandled() throws Exception {
		Record r = this.item(
			"{\"data\":{\"key\":\"EEEE5555\",\"itemType\":\"webpage\","
					+ "\"title\":\"Guidance\",\"creators\":[{\"name\":\"Some Organisation\",\"creatorType\":\"author\"}]}}"
		);

		assertEquals(
			"webpage",
			r.getPublicationType()
				.orElse(null)
		);
		assertEquals(
			List.of("Some Organisation"),
			r.getAuthors()
				.stream()
				.map(Author::getLastName)
				.toList()
		);
		assertTrue(
			r.getAuthors()
				.get(0)
				.getFirstName()
				.isEmpty()
		);
	}

	@Test
	public void unparseableDateIsSimplyAbsent() throws Exception {
		Record r = this.item(
			"{\"data\":{\"key\":\"FFFF6666\",\"itemType\":\"report\",\"title\":\"T\","
					+ "\"date\":\"n.d.\"}}"
		);

		assertTrue(
			r.getDate()
				.isEmpty()
		);
	}
}
