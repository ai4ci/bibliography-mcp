package uk.co.terminological.bibliography.api.openalex;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.record.Author;

/**
 * One {@code authorships[]} entry.
 *
 * OpenAlex separates the person ({@code author}, with an ORCID and a normalised display name) from the authorship (the
 * string as printed, plus institutions and affiliations). The person is what {@link Author} wants; the raw string is the
 * fallback for works where OpenAlex could not resolve a person.
 */
public class OpenAlexAuthor extends ExtensibleJson implements Author {

	public OpenAlexAuthor(JsonNode node) {
		super(node);
	}

	@Override
	public Optional<String> getORCID() {
		return this.streamPath("author", "orcid")
			.map(ExtensibleJson::asString)
			.findFirst()
			// an ORCID can be recorded against the authorship without resolving to an
			// OpenAlex person; it still names the same human to MergedRecord, which
			// keys author de-duplication on this
			.or(
				() -> this.asString("raw_orcid")
			);
	}

	private Optional<String> displayName() {
		return this.streamPath("author", "display_name")
			.map(ExtensibleJson::asString)
			.findFirst()
			.or(
				() -> this.asString("raw_author_name")
			);
	}

	/**
	 * OpenAlex gives one display name, so it is split at the last space. A name with no space (a mononym, or a raw string
	 * like "Challen R") becomes a surname with no forename, which is what the surname-only sources in this project already
	 * produce.
	 */
	@Override
	public String getLastName() {
		return this.displayName()
			.map(s -> s.contains(" ")
				? s.substring(s.lastIndexOf(' ') + 1)
				: s)
			.orElse("Unknown");
	}

	@Override
	public Optional<String> getFirstName() {
		return this.displayName()
			.filter(s -> s.contains(" "))
			.map(s -> s.substring(0, s.lastIndexOf(' ')));
	}

	/**
	 * Every forename initial, not just the first: "Catherine J. Hyams" initials as "CJ", because the source gave the
	 * middle name and dropping it would make two people out of one wherever another source spells it differently.
	 */
	@Override
	public Optional<String> getInitials() {
		return this.getFirstName()
			.map(
				first -> Arrays.stream(first.split("[\\s\\-\\u2010-\\u2015]"))
					.filter(p -> !p.isEmpty())
					.map(p -> p.substring(0, 1))
					.collect(Collectors.joining(""))
			)
			.filter(s -> !s.isEmpty());
	}

	/**
	 * The affiliation as the publisher printed it, falling back to the resolved institution name. OpenAlex offers both, and
	 * the raw string is the one that carries the department, so it wins where present.
	 */
	@Override
	public Stream<String> getAffiliations() {
		List<String> raw = this.streamPath("affiliations", "raw_affiliation_string")
			.map(ExtensibleJson::asString)
			.collect(Collectors.toList());
		if (!raw.isEmpty()) return raw.stream();
		return this.streamPath("institutions", "display_name")
			.map(ExtensibleJson::asString);
	}

}
