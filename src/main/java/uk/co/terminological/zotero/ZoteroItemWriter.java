package uk.co.terminological.zotero;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.PublicationTypes;
import uk.co.terminological.bibliography.record.Record;

/**
 * Turning a discovered record into the JSON for a **new** Zotero item.
 *
 * There is deliberately no mapping for updating an existing item: a Zotero item
 * may be scraped and broken or hand repaired, and nothing says which, so the
 * bridge never overwrites one. Where the API data disagrees with an existing item
 * the caller creates a duplicate and lets Zotero's duplicate detection (and the
 * user) resolve it — the agent decides, given the task.
 *
 * Only fields the record actually has are written: no placeholder dates, no
 * invented publishers.
 *
 * [IMPLEMENTS](/design/features/feat-005-zotero-curated-repository-bridge.md)
 */
public class ZoteroItemWriter {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static ObjectNode toItem(Record record, String collectionKey, List<String> tags) {
		String title = record.getTitle().orElse(null);
		if (title == null || title.trim().isEmpty()) {
			throw new IllegalArgumentException("cannot create a Zotero item without a title");
		}

		ObjectNode item = MAPPER.createObjectNode();
		item.put("itemType", PublicationTypes.toZotero(record.getPublicationType().orElse(null)));
		item.put("title", title);
		record.getAbstract().ifPresent(v -> item.put("abstractNote", v));
		record.getDate().ifPresent(v -> item.put("date", v.toString()));
		record.getPublisher().ifPresent(v -> item.put("publisher", v));
		record.getIdentifier(uk.co.terminological.bibliography.record.IdType.DOI).ifPresent(v -> item.put("DOI", v));
		record.getIdentifier(uk.co.terminological.bibliography.record.IdType.ISBN).ifPresent(v -> item.put("ISBN", v));
		// no landing-page field exists in the model; a PDF location is the honest
		// thing to record if that is all we have
		record.getPdfUri().ifPresent(v -> item.put("url", v.toString()));

		ArrayNode creators = item.putArray("creators");
		for (Author author : record.getAuthors()) {
			ObjectNode creator = creators.addObject();
			creator.put("creatorType", "author");
			String first = author.getFirstName().orElse("");
			if (first.trim().isEmpty()) {
				// an institutional author is a single name in Zotero
				creator.put("name", author.getLastName());
			} else {
				creator.put("firstName", first);
				creator.put("lastName", author.getLastName());
			}
		}

		ArrayNode tagArray = item.putArray("tags");
		for (String tag : tags) {
			if (tag != null && !tag.trim().isEmpty()) tagArray.addObject().put("tag", tag.trim());
		}

		ArrayNode collections = item.putArray("collections");
		if (collectionKey != null && !collectionKey.isEmpty()) collections.add(collectionKey);

		return item;
	}

	/**
	 * The object for a multi-item tag write: the item key, its version as a
	 * precondition, and the union of the tags it already has with the new ones.
	 *
	 * One request can carry up to 50 of these, which matters because a Zotero write
	 * authorisation is usually single-use: one request per item would ask the user to
	 * approve every single item, and Zotero allows only five permission prompts a
	 * minute.
	 */
	public static ObjectNode tagPatch(String itemKey, long version, JsonNode itemData, List<String> newTags) {
		ObjectNode patch = MAPPER.createObjectNode();
		patch.put("key", itemKey);
		patch.put("version", version);
		java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>(tagNames(itemData));
		for (String tag : newTags) {
			if (tag != null && !tag.trim().isEmpty()) union.add(tag.trim());
		}
		ArrayNode tagArray = patch.putArray("tags");
		union.forEach(t -> tagArray.addObject().put("tag", t));
		return patch;
	}

	/** the item's existing tags, for a union update that never drops one */
	public static List<String> tagNames(JsonNode itemData) {
		List<String> out = new java.util.ArrayList<>();
		JsonNode tags = itemData.path("tags");
		if (tags.isArray()) {
			JsonNode first = tags.get(0);
			if (first != null && first.isTextual()) {
				// some Zotero endpoints return tags as bare strings
				tags.forEach(t -> out.add(t.asText()));
			} else {
				tags.forEach(t -> out.add(t.path("tag").asText()));
			}
		}
		return out;
	}
}
