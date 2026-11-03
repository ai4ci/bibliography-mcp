package uk.co.terminological.bibliography.mcp;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.PrintReference;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * What a record looks like to an agent.
 *
 * One place, because an agent can only act on what reaches this JSON: authors were parsed by every client and emitted
 * by none of them until issue-018, and a score that is computed and dropped is a ranking the agent cannot see or
 * explain.
 *
 * The citation style is an argument rather than a mode. It used to be a static field set by a tool whose only job was
 * to set it (issue-019); naming the style on the call that returns records says the same thing without a mode to
 * forget.
 */
public class RecordJson {

	private RecordJson() {}

	/**
	 * One record.
	 *
	 * @param style a CSL style name, or null to render no citation
	 * @param score the score this record earned in the question being answered, or null when there is none
	 */
	public static ObjectNode of(
			ObjectMapper m, Record r, String style, Double score
	) {
		var n = m.createObjectNode();
		r.getIdentifier()
			.ifPresent(v -> n.put("id", v));
		n.put(
			"idType",
			r.getIdentifierType()
				.toString()
		);
		r.getTitle()
			.ifPresent(v -> n.put("title", v));
		r.getAbstract()
			.ifPresent(v -> n.put("abstract", v));
		r.getJournal()
			.ifPresent(v -> n.put("journal", v));
		r.getDate()
			.ifPresent(v -> n.put("date", v.toString()));
		r.getPdfUri()
			.ifPresent(v -> n.put("url", v.toString()));
		r.getIdentifier(IdType.PMID)
			.ifPresent(v -> n.put("PMID", v));
		r.getIdentifier(IdType.DOI)
			.ifPresent(v -> n.put("DOI", v));
		r.getIdentifier(IdType.PMCID)
			.ifPresent(v -> n.put("PMCID", v));
		r.getIdentifier(IdType.ISBN)
			.ifPresent(v -> n.put("ISBN", v));
		r.getPublicationType()
			.ifPresent(v -> n.put("publicationType", v));
		r.getPublisher()
			.ifPresent(v -> n.put("publisher", v));

		// Who wrote it. Without authors a record cannot be attributed, cited, or told
		// apart from another work of the same title (issue-018). A surname is the only
		// part every source supplies, so the rest appear only when the source gave them.
		if (!r.getAuthors()
			.isEmpty()) {
			var authors = n.putArray("authors");
			r.getAuthors()
				.forEach(a -> {
					var person = authors.addObject();
					person.put("lastName", a.getLastName());
					a.getFirstName()
						.ifPresent(v -> person.put("firstName", v));
					a.getInitials()
						.ifPresent(v -> person.put("initials", v));
					a.getORCID()
						.ifPresent(v -> person.put("ORCID", v));
				});
		}

		// Where it sits in print: only the sources that carry pagination have these.
		if (r instanceof PrintReference) {
			var print = (PrintReference) r;
			print.getVolume()
				.ifPresent(v -> n.put("volume", v));
			print.getIssue()
				.ifPresent(v -> n.put("issue", v));
			print.getPage()
				.ifPresent(v -> n.put("page", v));
		}

		var provenance = n.putObject("provenance");
		var p = r.getProvenance();
		provenance.put("source", p.getSource());
		p.getNativeType()
			.ifPresent(t -> provenance.put("nativeType", t));
		if (p.isCurated()) { provenance.put("curated", true); }

		if (score != null) { n.put("score", score); }
		if (style != null) {
			r.render(style)
				.ifPresent(v -> n.put("citation", v));
		}
		return n;
	}

	public static ObjectNode of(ObjectMapper m, Record r) {
		return of(m, r, null, null);
	}

	public static ArrayNode all(
			ObjectMapper m, Iterable<? extends Record> records, String style
	) {
		var out = m.createArrayNode();
		records.forEach(r -> out.add(of(m, r, style, null)));
		return out;
	}

	/** A citation edge: source cites target, the convention issue-007 settled. */
	public static ObjectNode of(ObjectMapper m, CitationLink l) {
		var n = m.createObjectNode();
		identifier(l.getSource()).ifPresent(v -> n.put("sourceId", v));
		l.getSource()
			.getTitle()
			.ifPresent(v -> n.put("sourceTitle", v));
		identifier(l.getTarget()).ifPresent(v -> n.put("targetId", v));
		l.getTarget()
			.getTitle()
			.ifPresent(v -> n.put("targetTitle", v));
		l.getIndex()
			.ifPresent(v -> n.put("citationIndex", v));
		return n;
	}

	private static Optional<String> identifier(
			uk.co.terminological.bibliography.record.CitationReference reference
	) {
		return reference.getIdentifier()
			.flatMap(RecordReference::getIdentifier);
	}

}
