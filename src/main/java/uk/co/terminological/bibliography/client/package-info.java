/**
 * The capability interfaces an API client implements: what a bibliographic source can be <em>asked</em>, as opposed to
 * {@code ..bibliography.record}, which is what it can <em>say</em>.
 *
 * There is no single {@code BibliographicClient} interface, and that is the main design decision in this package. The
 * sources genuinely differ in what they can do: OpenCitations only knows about citation edges and holds no
 * metadata; CrossRef and medRxiv can hand over a
 * work's own reference list but cannot say who cites it; Entrez does all five. One fat interface would force every
 * client to stub out most of it, and would leave callers unable to tell "this API has nothing for you" from "this API
 * cannot be asked". Instead a client implements only the interfaces it can honour, and {@code BiblioFacade} keeps a
 * routing table per capability — so adding an API means implementing the subset it supports and naming it in {@code
 * BiblioFacade.defaultApis()}.
 *
 * <h2>The five capabilities</h2>
 *
 * {@link uk.co.terminological.bibliography.client.Searcher} — terms in, records out. Discovery.
 *
 * {@link uk.co.terminological.bibliography.client.RecordFetcher} — identifiers in, records out, keyed by the
 * identifier asked for. Resolution of something already named.
 *
 * {@link uk.co.terminological.bibliography.client.CitesMapper} — what does this work cite? Its reference list, the
 * outgoing edges.
 *
 * {@link uk.co.terminological.bibliography.client.CitedByMapper} — what cites this work? The incoming edges.
 *
 * {@link uk.co.terminological.bibliography.client.IdMapper} — which other identifiers name this same work?
 *
 * The two citation directions are separate interfaces rather than one method with a direction argument precisely
 * because that asymmetry is real: a publisher knows what its own articles cite but not who cited them afterwards.
 * Splitting them lets the facade route "expand downwards" and "expand upwards" to different sets of APIs.
 *
 * <h2>Batch in, batch out</h2>
 *
 * Every capability takes a {@code Collection} of references and returns a collection or map, with a convenience
 * default for the single-value case. The batch call is the primitive, not an optimisation of the single call: these
 * APIs are rate-limited and most of them accept many identifiers per request, so a loop over single fetches is the
 * expensive way to do it. Writing the batch form as the interface method means a client cannot accidentally offer only
 * the slow path.
 *
 * Return types are consistently {@code Collection<? extends X>} because implementations return their own payload
 * classes — {@code CrossRefWork}, {@code EntrezEntry} — rather than copies, so the raw response remains reachable.
 *
 * <h2>Queries</h2>
 *
 * {@link uk.co.terminological.bibliography.client.Query} is structured rather than a search string, because the query
 * syntaxes are mutually incompatible and a string would have to be written for one specific API. The caller states
 * intent — these terms anywhere, these in title or abstract, these authors, this journal, this date window, this many
 * results — and each client renders that into its own syntax. {@code getNativeQuery()} is the deliberate escape hatch
 * for a caller who knows the target API and wants its syntax passed through untouched.
 *
 * {@link uk.co.terminological.bibliography.client.SearchBuilder} is the alternative to {@code Searcher.search} for
 * callers who want the API's own result type rather than the common {@code Record} shape: it is parameterised on that
 * type, so nothing is flattened away.
 */
package uk.co.terminological.bibliography.client;
