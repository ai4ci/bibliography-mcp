package uk.co.terminological.zotero;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.PlainText;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.Record;

/**
 * Compares a Zotero item against what the bibliographic APIs say about the same
 * work, field by field, and reports the differences. It never picks a winner.
 *
 * That is deliberate. Whether to keep `2019` or take `2019-01-01`, whether a
 * publisher rename is an improvement, whether a difference needs the user at all —
 * these are judgement calls for the agent, in conversation with the user. A rule
 * hidden in here would make the same decision silently and wrongly some of the
 * time; a report makes it visible every time.
 *
 * Markup in a value is reported rather than hidden: a field whose Zotero value
 * carries JATS or HTML is flagged, because cleaning both sides for the comparison
 * would otherwise make a marked-up abstract look like an agreement and leave the
 * mess in the library.
 *
 * [IMPLEMENTS](/design/features/feat-008-clean-up-collection-metadata.md)
 */
public class MetadataComparison {

	/** the Zotero field, the API accessor, and how to normalise both */
	private enum Field {
		TITLE("title", "title"),
		DATE("date", "date"),
		ABSTRACT("abstractNote", "abstractNote"),
		PUBLISHER("publisher", "publisher"),
		CONTAINER("container", "container"),
		DOI("DOI", "DOI"),
		URL("url", "url");

		final String zoteroName;
		final String reportName;

		Field(String zoteroName, String reportName) {
			this.zoteroName = zoteroName;
			this.reportName = reportName;
		}
	}

	public static final String AGREE = "agree";
	public static final String FILLABLE = "fillable";
	public static final String CONFLICT = "conflict";
	public static final String ZOTERO_ONLY = "zotero-only";

	public static final String ALL_AGREE = "agree";
	public static final String GAPS = "gaps";
	public static final String CONFLICTS = "conflicts";

	/**
	 * @param zoteroData the item's `data` node
	 * @param apiRecords what the APIs say, each carrying its own provenance
	 */
	public static ObjectNode compare(ObjectMapper mapper, JsonNode zoteroData, List<? extends Record> apiRecords) {
		ObjectNode out = mapper.createObjectNode();
		ObjectNode fields = out.putObject("fields");
		Map<String, String> classes = new LinkedHashMap<>();
		int markup = 0;

		for (Field field : Field.values()) {
			// a container has no single Zotero field name, so the report names the one
			// this item actually uses: an update can then be written from it directly
			String fieldName = field == Field.CONTAINER ? containerFieldName(zoteroData) : field.zoteroName;
			String zoteroValue = valueOf(zoteroData, fieldName, false);
			Map<String, String> apiValues = apiValues(apiRecords, field);
			String classification = classify(zoteroValue, apiValues);
			if (classification == null) continue; // neither side has anything to say

			ObjectNode entry = fields.putObject(field == Field.CONTAINER ? fieldName : field.reportName);
			if (zoteroValue != null) entry.put("zotero", zoteroValue);
			if (!apiValues.isEmpty()) {
				ObjectNode apis = entry.putObject("apis");
				apiValues.forEach(apis::put);
			}
			entry.put("class", classification);
			if (PlainText.hasMarkup(zoteroValue)) {
				// the two values agree once markup is ignored, but the library still
				// holds the markup: say so, or it stays there for good
				entry.put("zoteroHasMarkup", true);
				markup++;
			}
			classes.put(field.reportName, classification);
		}

		String creators = addCreators(mapper, fields, zoteroData, apiRecords);
		if (creators != null) classes.put("creators", creators);

		// the summary is derived from the fields, so it cannot drift from them
		if (markup > 0) out.put("fieldsWithMarkup", markup);
		out.put("classification", classes.containsValue(CONFLICT) ? CONFLICTS
				: classes.containsValue(FILLABLE) ? GAPS
				: ALL_AGREE);
		return out;
	}

	private static String classify(String zoteroValue, Map<String, String> apiValues) {
		if (zoteroValue == null && apiValues.isEmpty()) return null;
		if (zoteroValue == null) return FILLABLE;
		if (apiValues.isEmpty()) return ZOTERO_ONLY;
		String normalised = ZoteroMatching.text(PlainText.of(zoteroValue));
		boolean allAgree = apiValues.values()
				.stream()
				.map(value -> ZoteroMatching.text(PlainText.of(value)))
				.allMatch(normalised::equals);
		return allAgree ? AGREE : CONFLICT;
	}

	/** the container field this item type uses, defaulting to the article one */
	private static String containerFieldName(JsonNode data) {
		for (String candidate : CONTAINER_FIELDS) {
			JsonNode node = data.get(candidate);
			if (node != null && !node.isNull() && !node.asText().trim().isEmpty()) return candidate;
		}
		return "publicationTitle";
	}

	private static final String[] CONTAINER_FIELDS = {
			"publicationTitle", "bookTitle", "proceedingsTitle", "encyclopediaTitle",
			"dictionaryTitle", "websiteTitle", "blogTitle", "repository" };

	private static String valueOf(JsonNode data, String fieldName, boolean clean) {
		JsonNode node = data.get(fieldName);
		if (node == null || node.isNull()) return null;
		String value = node.asText();
		if (value == null || value.trim().isEmpty()) return null;
		return clean ? PlainText.of(value) : value.trim();
	}

	private static Map<String, String> apiValues(List<? extends Record> apiRecords, Field field) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Record record : apiRecords) {
			Optional<String> value = read(record, field);
			// attributed by source, and cleaned: the agent needs to know who said what,
			// and to be able to copy the value straight into an update
			value.map(PlainText::of).ifPresent(v -> out.putIfAbsent(sourceOf(record), v));
		}
		return out;
	}

	private static Optional<String> read(Record record, Field field) {
		switch (field) {
			case TITLE: return record.getTitle();
			case DATE: return record.getDate().map(LocalDate::toString);
			case ABSTRACT: return record.getAbstract();
			case PUBLISHER: return record.getPublisher();
			case CONTAINER: return record.getContainerTitle();
			case DOI: return record.getIdentifier(IdType.DOI);
			case URL: return record.getPdfUri().map(Object::toString);
			default: return Optional.empty();
		}
	}

	private static String sourceOf(Record record) {
		String source = record.getProvenance().map(Provenance::getSource).orElse("");
		return source.isEmpty() ? record.getClass().getSimpleName() : source;
	}

	/**
	 * Creators are reported as a count and a set of surnames, not as a proposed
	 * replacement list: author lists differ legitimately between sources (initials,
	 * ordering, one source listing an editor), and only the agent can judge whether a
	 * difference is worth acting on.
	 */
	private static String addCreators(ObjectMapper mapper, ObjectNode fields, JsonNode zoteroData,
			List<? extends Record> apiRecords) {
		List<String> zoteroSurnames = zoteroSurnames(zoteroData);
		Map<String, String> apiCounts = new LinkedHashMap<>();
		Map<String, List<String>> apiSurnames = new LinkedHashMap<>();
		for (Record record : apiRecords) {
			List<String> surnames = record.getAuthors()
				.stream()
				.map(Author::getLastName)
				.filter(s -> s != null && !isPlaceholder(s))
				.toList();
			if (surnames.isEmpty()) continue;
			apiCounts.put(sourceOf(record), String.valueOf(surnames.size()));
			apiSurnames.put(sourceOf(record), surnames);
		}
		if (zoteroSurnames.isEmpty() && apiCounts.isEmpty()) return null;

		ObjectNode entry = fields.putObject("creators");
		if (!zoteroSurnames.isEmpty()) {
			entry.put("zotero", String.valueOf(zoteroSurnames.size()));
			entry.putArray("zoteroSurnames").addAll(textNodes(mapper, zoteroSurnames));
		}
		if (!apiCounts.isEmpty()) {
			ObjectNode apis = entry.putObject("apis");
			ObjectNode surnames = entry.putObject("apiSurnames");
			apiCounts.forEach(apis::put);
			apiSurnames.forEach((source, list) -> surnames.putArray(source).addAll(textNodes(mapper, list)));
		}

		String classification;
		if (zoteroSurnames.isEmpty()) {
			classification = FILLABLE;
		} else if (apiCounts.isEmpty()) {
			classification = ZOTERO_ONLY;
		} else {
			String normalised = normaliseSurnames(zoteroSurnames);
			classification = apiSurnames.values()
					.stream()
					.map(MetadataComparison::normaliseSurnames)
					.anyMatch(s -> !s.equals(normalised)) ? CONFLICT : AGREE;
		}
		entry.put("class", classification);
		entry.put("note", "creators are reported for judgement; an update must supply the creators array itself");
		return classification;
	}

	private static List<JsonNode> textNodes(ObjectMapper mapper, List<String> values) {
		return values.stream().map(v -> (JsonNode) mapper.getNodeFactory().textNode(v)).toList();
	}

	/**
	 * Unpaywall reports placeholders ("Unknown") when it has no author data, which
	 * would otherwise read as a disagreement with every item it appears on and train
	 * a reader to ignore the creators line altogether.
	 */
	private static boolean isPlaceholder(String surname) {
		String normalised = ZoteroMatching.text(surname);
		if (normalised == null) return true;
		// "AUTHOR UNKNOWN" (Europe PMC's placeholder for an unmatched reference) and
		// friends are not names
		if (normalised.contains("unknown")) return true;
		return java.util.Set.of("na", "none", "anonymous", "noname").contains(normalised);
	}

	private static List<String> zoteroSurnames(JsonNode data) {
		return java.util.stream.StreamSupport
				.stream(data.path("creators").spliterator(), false)
				.map(c -> c.path("lastName").asText(c.path("name").asText("")))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
	}

	private static String normaliseSurnames(List<String> surnames) {
		return surnames.stream()
				.map(ZoteroMatching::text)
				.filter(s -> s != null)
				.sorted()
				.collect(java.util.stream.Collectors.joining("|"))
				.toLowerCase(Locale.ROOT);
	}
}
