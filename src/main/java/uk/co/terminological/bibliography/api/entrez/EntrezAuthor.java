package uk.co.terminological.bibliography.api.entrez;

import java.util.Optional;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

public class EntrezAuthor implements Author, Raw<XmlElement> {

	private XmlElement raw;
	public EntrezAuthor(XmlElement raw) {
		this.raw = raw;
	}
	public String getLastName() {
		return raw.childElements("LastName")
			.findFirst()
			.flatMap(o -> o.getTextContent())
			.orElse("Unknown");
	}
	public Optional<String> getFirstName() {
		return raw.childElements("ForeName")
			.findFirst()
			.flatMap(o -> o.getTextContent());
	}
	public Optional<String> getInitials() {
		return raw.childElements("Initials")
			.findFirst()
			.flatMap(o -> o.getTextContent());
	}
	public Optional<String> getORCID() {
		try {
			return raw.doXpath("./Identifier[@Source='ORCID']")
				.get(XmlElement.class)
				.flatMap(o -> o.getTextContent());
		} catch (XmlException e) {
			return Optional.empty();
		}
	}
	public Stream<String> getAffiliations() {
		return raw.childElements("AffiliationInfo")
			.stream()
			.flatMap(
				el -> el.childElements("Affiliation")
					.stream()
			)
			.flatMap(
				o -> o.getTextContent()
					.stream()
			);
	}

	public String toString() {
		return getLastName() + ", " + getInitials().orElse("Unknown");
	}
	@Override
	public XmlElement getRaw() { return raw; }

}