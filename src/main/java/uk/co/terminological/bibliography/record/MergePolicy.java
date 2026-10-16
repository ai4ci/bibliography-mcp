package uk.co.terminological.bibliography.record;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * How merged records resolve conflicting field values.
 *
 * A documented per-field priority order over sources, so the same inputs always produce the same record no matter which
 * order the APIs answered in. There are no heuristics: "longest abstract wins" would be deterministic in letter but
 * unpredictable in spirit, whereas an explicit table can be read and argued with.
 *
 * Every source is ranked the same way, including a Zotero item. A zotero record may be scraped and broken or hand
 * repaired, and nothing reliably distinguishes the two, so curation lives in the write-back rules (never overwrite a
 * zotero item — see feat-005) rather than in field precedence here. A curated record keeps its own provenance so the
 * item it came from stays recoverable.
 *
 * [IMPLEMENTS](/design/features/feat-007-predictable-record-merging.md)
 */
public class MergePolicy implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String TITLE = "title";
	public static final String ABSTRACT = "abstract";
	public static final String CONTAINER = "containerTitle";
	public static final String DATE = "date";
	public static final String PDF = "pdfUri";
	public static final String PUBLICATION_TYPE = "publicationType";
	public static final String PUBLISHER = "publisher";
	public static final String AUTHORS = "authors";
	public static final String LICENCES = "licences";
	public static final String CITATIONS = "citations";

	/** used for fields with no explicit table entry, and to order list-valued fields */
	private static final List<String> FALLBACK = List.of(
		Provenance.CROSSREF,
		Provenance.EUROPEPMC,
		Provenance.ENTREZ,
		Provenance.UNPAYWALL,
		// the preprint servers come after the indexed services: a preprint's copy
		// of its own abstract is authoritative, but a PMC record of the same work
		// is the better source where both exist
		Provenance.MEDRXIV,
		Provenance.BIORXIV,
		Provenance.ARXIV,
		Provenance.ZOTERO
	);

	private final Map<String, List<String>> fieldPriority;

	private MergePolicy(Map<String, List<String>> fieldPriority) {
		this.fieldPriority = fieldPriority;
	}

	public static MergePolicy standard() {
		return new MergePolicy(
				Map.of(
					// a registration agency is the best source of a title and date;
					// abstracts are better in Europe PMC and Entrez
					TITLE,
					List.of(
						Provenance.CROSSREF,
						Provenance.EUROPEPMC,
						Provenance.ENTREZ,
						Provenance.MEDRXIV,
						Provenance.BIORXIV,
						Provenance.ARXIV
					),
					ABSTRACT,
					List.of(
						Provenance.EUROPEPMC,
						Provenance.ENTREZ,
						Provenance.CROSSREF,
						Provenance.MEDRXIV,
						Provenance.BIORXIV,
						Provenance.ARXIV
					),
					CONTAINER,
					List.of(
						Provenance.CROSSREF,
						Provenance.EUROPEPMC,
						Provenance.ENTREZ,
						Provenance.MEDRXIV,
						Provenance.BIORXIV,
						Provenance.ARXIV
					),
					DATE,
					List.of(
						Provenance.CROSSREF,
						Provenance.ENTREZ,
						Provenance.EUROPEPMC,
						Provenance.MEDRXIV,
						Provenance.BIORXIV,
						Provenance.ARXIV
					),
					PDF,
					List.of(
						Provenance.UNPAYWALL,
						Provenance.EUROPEPMC,
						Provenance.CROSSREF
					),
					PUBLICATION_TYPE,
					List.of(
						Provenance.CROSSREF,
						Provenance.EUROPEPMC,
						Provenance.MEDRXIV,
						Provenance.BIORXIV,
						Provenance.ARXIV,
						Provenance.ZOTERO
					),
					PUBLISHER,
					List.of(Provenance.CROSSREF, Provenance.ZOTERO),
					AUTHORS,
					FALLBACK,
					LICENCES,
					FALLBACK,
					CITATIONS,
					FALLBACK
				)
		);
	}

	public List<String> priorityFor(String field) {
		return this.fieldPriority.getOrDefault(field, FALLBACK);
	}

	/** ranked before everything else, for the record a preprint server owns */
	private static final int PREPRINT_SERVER_RANK = -1;

	/** Lower is better; an unknown source ranks last rather than being dropped. */
	public int rank(String field, Record record) {
		var source = sourceOf(record);
		// A preprint server is the source of record for its own preprint and it
		// updates first — a v2 correction appears there while the indexed services
		// hold whatever they captured at indexing time — so for a posted-content
		// record the server outranks them. For a published work the usual order
		// stands, and the server has no publisher to offer anyway.
		if (isPreprintServer(source) && isPostedContent(record))
			return PREPRINT_SERVER_RANK;
		var index = this.priorityFor(field)
			.indexOf(source);
		return index < 0 ? Integer.MAX_VALUE : index;
	}

	private static boolean isPreprintServer(String source) {
		return List.of(Provenance.MEDRXIV, Provenance.BIORXIV, Provenance.ARXIV)
			.contains(source);
	}

	private static boolean isPostedContent(Record record) {
		return record.getPublicationType()
			.map(PublicationTypes.POSTED_CONTENT::equals)
			.orElse(false);
	}

	public static String sourceOf(Record record) {
		return record.getProvenance()
			.getSource();
	}
}
