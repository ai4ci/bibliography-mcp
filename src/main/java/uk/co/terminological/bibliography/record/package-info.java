/**
 * The unified record model: one vocabulary for works, the claims made about them, and the edges between them,
 * independent of which API supplied any of it.
 *
 * Every API in this project speaks its own dialect. The point of this package is that a caller never has to know which
 * one answered: a CrossRef work, a PubMed entry, a Europe PMC hit and a Zotero item all arrive as a {@link
 * uk.co.terminological.bibliography.record.Record}, and a reference list from any of them arrives as {@link
 * uk.co.terminological.bibliography.record.CitationLink}s. The API-specific classes under {@code ..bibliography.api}
 * implement these interfaces directly over their own payload rather than copying into a transfer object, so nothing is
 * lost in translation and the raw response stays reachable (see {@link uk.co.terminological.bibliography.record.Raw}).
 *
 * <h2>Why there are so many similar-looking reference types</h2>
 *
 * The types here look alike because they all, in some sense, "point at a published work". They are distinct because
 * they differ in <em>how much is known</em> about the work being pointed at, and the whole model turns on not
 * pretending to know more than the source said. Three axes:
 *
 * <h3>1. Identity — how is the work named?</h3>
 *
 * {@link uk.co.terminological.bibliography.record.RecordReference} is the minimum: an {@link
 * uk.co.terminological.bibliography.record.IdType} and a string. Nothing else — no title, no claims. It is the identity
 * every other type is built on, and the thing a citation graph has nodes of. Its identifier is an {@code Optional}
 * because parsing a reference can fail to produce one; {@code isComplete()} is the test, and callers that need a real
 * node (notably {@code Session}) drop the ones that fail it.
 *
 * {@link uk.co.terminological.bibliography.record.CitationReference} is a work <em>as it appears in someone's reference
 * list</em>, and is deliberately <strong>not</strong> a {@code RecordReference}. A reference list entry frequently
 * carries no identifier at all — "Smith J et al. Lancet 2011;377:1234-42" names a real paper perfectly well without a
 * DOI — so this type <em>holds</em> an optional identifier alongside the two things you can fall back on when there is
 * none: a title, and the print coordinates. It is the only reference type that can describe a work it cannot identify,
 * which is exactly why it exists separately.
 *
 * {@link uk.co.terminological.bibliography.record.PrintReference} is not an identifier scheme at all but a
 * <em>coordinate</em>: journal, volume, issue, page, year, first author surname — where the work physically sits in the
 * literature. It has two jobs: the fallback descriptor inside a {@code CitationReference}, and the extra detail mixed
 * into records from sources that supply pagination. It is also the shape a citation processor needs in order to render
 * "Smith et al., Lancet 377(9775):1234".
 *
 * <h3>2. Content — what is claimed about the work?</h3>
 *
 * {@link uk.co.terminological.bibliography.record.Record} extends {@code RecordReference} with the bibliographic claims
 * — title, abstract, authors, container, date, pdf, publication type, publisher — and a {@link
 * uk.co.terminological.bibliography.record.Provenance} saying who is making them. A {@code Record} is a
 * <strong>statement about a work by one source</strong>, not a library entry: notes, tags, collections and attachments
 * belong to a repository and stay out of the model on purpose.
 *
 * {@link uk.co.terminological.bibliography.record.RecordWithCitations} adds the citation edges that arrived in the same
 * payload. It is a separate type rather than a method on {@code Record} because most fetches do not return a reference
 * list, and an empty list would otherwise be indistinguishable from an unasked question.
 *
 * {@link uk.co.terminological.bibliography.record.PrintRecord} is the intersection of {@code Record} and {@code
 * PrintReference}, for sources that give pagination along with the metadata (Entrez and the Europe PMC core shape).
 * {@link uk.co.terminological.bibliography.record.PrintRecordWithCitations} is the all-three combination.
 *
 * {@link uk.co.terminological.bibliography.record.MergedRecord} is the odd one out: the only implementation that is not
 * any single API's view. It holds every contributing record and answers each field through {@link
 * uk.co.terminological.bibliography.record.MergePolicy}, a documented per-field source priority, so the same inputs
 * always give the same answer regardless of which API replied first. Losers are retained rather than discarded, so a
 * disagreement between sources can be reported instead of silently resolved.
 *
 * <h3>3. Edges — pairs of references, and why two kinds</h3>
 *
 * {@link uk.co.terminological.bibliography.record.CitationLink} and {@link
 * uk.co.terminological.bibliography.record.RecordReferenceMapping} have the same shape — a source and a target — and
 * opposite meanings. A {@code CitationLink} relates <strong>two different works</strong>: source cites target, with an
 * optional index giving its position in the reference list. A {@code RecordReferenceMapping} relates <strong>two names
 * for one work</strong>: a DOI and the PMID of the same article, or a preprint and the article it became. Confusing
 * them is not a cosmetic error — treating a mapping as a citation puts a self-loop in the graph, and treating a
 * citation as a mapping collapses two papers into one.
 *
 * Their endpoint types follow from that distinction. A {@code CitationLink}'s endpoints are {@code
 * CitationReference}s, because reference lists contain entries that cannot be identified and the model has to be able
 * to say so. A {@code RecordReferenceMapping}'s endpoints are {@code RecordReference}s, because an identifier mapping
 * with a missing identifier asserts nothing.
 *
 * <h2>Supporting types</h2>
 *
 * {@link uk.co.terminological.bibliography.record.IdType} is the identifier scheme. {@link
 * uk.co.terminological.bibliography.record.PublicationTypes} is the CSL kind-of-work vocabulary and the mappings into
 * it. {@link uk.co.terminological.bibliography.record.Author} is a contributor. {@link
 * uk.co.terminological.bibliography.record.PlainText} strips the markup that arrives inside metadata fields. {@link
 * uk.co.terminological.bibliography.record.Builder} is the one place these are constructed, and {@code Immutable*}
 * classes are generated from the interfaces by the Immutables annotation processor — which is why the interfaces carry
 * {@code @Value.Immutable} and why a plain {@code default} method on one of them is <em>not</em> a copied attribute
 * unless it is also marked {@code @Value.Default}.
 */
package uk.co.terminological.bibliography.record;
