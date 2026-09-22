package uk.co.terminological.bibliography.opencitations;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.CitationReference;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableCitationReference;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;

public class OpenCitationsCitation extends ExtensibleJson implements CitationLink {
	
	public OpenCitationsCitation(JsonNode node) { super(node); }
	
	public Optional<String> getCitingDoi() {return this.asString("citing").map(OpenCitationsCitation::doiFromPids);}
	public Optional<String> getCitedDoi() {return this.asString("cited").map(OpenCitationsCitation::doiFromPids);}
	public Optional<String> getCitingDate() {return this.asString("creation").map(OpenCitationsCitation::value);}
	public Optional<String> getCitationId() {return this.asString("oci").map(OpenCitationsCitation::value);}
	public Optional<String> getCitationIndex() {return this.asString("oci").map(OpenCitationsCitation::key);}
	
	private static String value(String response) {
		if (response.contains(" => "))
			return response.split(" => ")[1];
		else
			return response;
	}

	/**
	 * v2 citing/cited fields are space-separated pid lists e.g.
	 * "omid:br/06101801781 doi:10.7717/peerj-cs.421 pmid:33817056" — extract the doi.
	 * Falls back to a bare doi for the old v1 plain-doi shape. Returns null when the
	 * entity has no doi (incomplete CitationLink; callers skip those).
	 */
	private static String doiFromPids(String response) {
		String v = value(response);
		for (String tok : v.split(";")) {
			for (String pid : tok.trim().split("\\s+")) {
				if (pid.startsWith("doi:")) return pid.substring(4);
			}
		}
		if (!v.contains(":")) return v; // v1-style bare doi
		return null;
	}
	
	private static String key(String response) {
		if (response.contains(" => "))
			return response.split(" => ")[0];
		else
			return null;
	}

	@Override
	public CitationReference getSource() {
		return new ImmutableCitationReference(
				new ImmutableRecordReference(IdType.DOI, getCitingDoi().orElse(null)), null, null);
	}

	@Override
	public CitationReference getTarget() {
		return new ImmutableCitationReference(
				new ImmutableRecordReference(IdType.DOI, getCitedDoi().orElse(null)), null, null);
	}

	@Override
	public Optional<Integer> getIndex() {
		try {
			return getCitationIndex().map(Integer::parseInt);
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}
	
}
