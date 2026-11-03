package uk.co.terminological.bibliography.api.opencitations;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.CitationReference;
import uk.co.terminological.bibliography.record.IdType;

public class OpenCitationsCitation extends ExtensibleJson
		implements CitationLink {

	public OpenCitationsCitation(JsonNode node) {
		super(node);
	}

	public Optional<String> getCitingDoi() {
		return this.asString("citing")
			.map(OpenCitationsCitation::doiFromPids);
	}
	public Optional<String> getCitedDoi() {
		return this.asString("cited")
			.map(OpenCitationsCitation::doiFromPids);
	}
	public Optional<String> getCitingDate() {
		return this.asString("creation")
			.map(OpenCitationsCitation::value);
	}
	public Optional<String> getCitationId() {
		return this.asString("oci")
			.map(OpenCitationsCitation::value);
	}
	public Optional<String> getCitationIndex() {
		return this.asString("oci")
			.map(OpenCitationsCitation::key);
	}

	private static String value(String response) {
		if (response.contains(" => "))
			return response.split(" => ")[1];
		else
			return response;
	}

	/**
	 * v2 citing/cited fields are space-separated pid lists e.g. "omid:br/06101801781 doi:10.7717/peerj-cs.421
	 * pmid:33817056" — extract the doi. Falls back to a bare doi for the old v1 plain-doi shape. Returns null when the
	 * entity has no doi at all, which is now only used by {@link #getCitingDoi()}; {@link #getSource()} keeps whatever
	 * identifier there is (see {@link #pidFromPids}).
	 */
	private static String doiFromPids(String response) {
		var v = value(response);
		for (String tok : v.split(";")) {
			for (String pid : tok.trim()
				.split("\\s+")) {
				if (pid.startsWith("doi:")) return pid.substring(4);
			}
		}
		if (!v.contains(":")) return v; // v1-style bare doi
		return null;
	}

	/**
	 * Any identifier for the entity: the doi when there is one, otherwise the first PID in the list (an `omid:` or
	 * `openalex:` handle). OpenCitations gives handles and nothing else — no titles — so without this the link had neither
	 * an identifier nor a title, and could be neither shown nor deduplicated.
	 */
	/**
	 * The schemes this project can name, best first.
	 *
	 * Order is "how much of this project's graph it joins", not preference for its own sake. A DOI is understood by
	 * every source here; a PMID by three; a PMCID by two; an OpenAlex handle by one — but that one can resolve it,
	 * which is the whole reason this stopped being {@code UNK} (issue-001, debt-011). 40 of the 42 cited entities in
	 * a real response carry an OpenAlex handle.
	 */
	private static final java.util.List<java.util.Map.Entry<String, IdType>> KNOWN_SCHEMES = java.util.List
		.of(
			java.util.Map.entry("doi:", IdType.DOI),
			java.util.Map.entry("pmid:", IdType.PMID),
			java.util.Map.entry("pmcid:", IdType.PMCID),
			java.util.Map.entry("openalex:", IdType.OPENALEX),
			java.util.Map.entry("isbn:", IdType.ISBN)
		);

	static Optional<Pid> pidFromPids(String response) {
		var v = value(response);
		for (var scheme : KNOWN_SCHEMES) {
			for (String tok : v.split(";")) {
				for (String pid : tok.trim()
					.split("\\s+")) {
					if (pid.startsWith(scheme.getKey())) return Optional.of(
							new Pid(
									scheme.getValue(),
									pid.substring(
										scheme.getKey()
											.length()
									)
							)
					);
				}
			}
		}
		if (!v.contains(":")) return v.isEmpty() ? Optional.empty()
			: Optional.of(new Pid(IdType.DOI, v));
		// a scheme this project does not model — an omid or an arxiv id. Kept whole,
		// scheme and all, because it still identifies the entity and a link with an
		// identifier beats a link with none
		for (String tok : v.split(";")) {
			for (String pid : tok.trim()
				.split("\\s+")) {
				if (pid.contains(":")) return Optional.of(new Pid(IdType.UNK, pid));
			}
		}
		return Optional.empty();
	}

	/** an identifier and the scheme it belongs to, which may be one we do not model */
	static final class Pid {
		final IdType type;
		final String value;

		Pid(IdType type, String value) {
			this.type = type;
			this.value = value;
		}
	}

	private static String key(String response) {
		if (response.contains(" => "))
			return response.split(" => ")[0];
		else
			return null;
	}

	@Override
	public CitationReference getSource() { return this.reference("citing"); }

	@Override
	public CitationReference getTarget() { return this.reference("cited"); }

	private CitationReference reference(String field) {
		var identifier = this.asString(field)
			.flatMap(OpenCitationsCitation::pidFromPids)
			.map(pid -> Builder.recordReference(pid.type, pid.value))
			.orElse(null);
		return Builder
			.citationReference(identifier, Optional.empty(), Optional.empty());
	}

	@Override
	public Optional<Integer> getIndex() {
		try {
			return this.getCitationIndex()
				.map(Integer::parseInt);
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

}
