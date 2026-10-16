package uk.co.terminological.bibliography.api.opencitations;

import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;

public class OpenCitationsListResult extends ExtensibleJson {

	public OpenCitationsListResult(JsonNode node) {
		super(node);
	}

	public Stream<OpenCitationsCitation> getCitations() {
		return this.streamNode(OpenCitationsCitation.class);
	}

}
