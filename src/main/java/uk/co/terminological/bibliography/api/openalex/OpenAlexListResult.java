package uk.co.terminological.bibliography.api.openalex;

import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import uk.co.terminological.bibliography.ExtensibleJson;

/**
 * A paged OpenAlex list response: {@code meta} plus {@code results}.
 *
 * Two numbers in {@code meta} are easy to confuse and expensive to get wrong. {@code count} is the size of the whole
 * population matching the query; the number of items in this response is the page. Reading one as the other silently
 * truncates a crawl at the page size, so both are exposed and neither is inferred from the other. Paging uses
 * {@code cursor}, since OpenAlex's deep paging is cursor based.
 *
 * @param <X> the element type this list carries
 */
public class OpenAlexListResult<X extends ExtensibleJson> extends ExtensibleJson {

	private Class<X> clazz;

	protected OpenAlexListResult(Class<X> clazz, JsonNode raw) {
		super(raw);
		this.clazz = clazz;
	}

	/** The works on this page. Empty for a body that carried no {@code results} array at all — check {@link #isWellFormed()}. */
	public Stream<X> getItems() {
		return this.streamPath(this.clazz, "results");
	}

	/** How many works match the query in total, which is more than this page whenever {@link #nextCursor()} is present. */
	public Optional<Long> hitCount() {
		return this.streamPath("meta", "count")
			.findFirst()
			.map(ExtensibleJson::asString)
			.flatMap(s -> {
				try {
					return Optional.of(Long.parseLong(s));
				} catch (NumberFormatException e) {
					return Optional.empty();
				}
			});
	}

	/** The cursor for the next page, or absent once the population is exhausted. */
	public Optional<String> nextCursor() {
		return this.streamPath("meta", "next_cursor")
			.map(ExtensibleJson::asString)
			.findFirst();
	}

	/**
	 * Whether this is a complete OpenAlex list response.
	 *
	 * A truncated body — the prefix of a JSON document is often itself valid JSON, and {@code []} parses cleanly — yields
	 * an empty item stream, so without this check the client cannot tell "nothing matched" from "the response broke", and
	 * the broken body gets cached as a lasting empty answer. A complete response always carries {@code meta} or
	 * {@code results}; an empty result set is one of those with an empty array, which is why a bare {@code {}} is rejected.
	 *
	 * See issue-004 (truncated responses are cached as empty); a link edge to an issue is not valid from production code,
	 * so this is a prose reference.
	 */
	public boolean isWellFormed() {
		return this.getRaw()
			.isArray()
				|| this.getRaw()
					.has("meta")
				|| this.getRaw()
					.has("results");
	}

	/** A list of works. */
	public static class Works extends OpenAlexListResult<OpenAlexRecord> {
		public Works(JsonNode raw) {
			super(OpenAlexRecord.class, raw);
		}

		public static Works empty() {
			return new Works(JsonNodeFactory.instance.arrayNode());
		}
	}

	/** A list of journals and repositories — the {@code /sources} endpoint, used to resolve a journal name to an ISSN. */
	public static class Sources extends OpenAlexListResult<ExtensibleJson> {
		public Sources(JsonNode raw) {
			super(ExtensibleJson.class, raw);
		}

		public static Sources empty() {
			return new Sources(JsonNodeFactory.instance.arrayNode());
		}
	}

}
