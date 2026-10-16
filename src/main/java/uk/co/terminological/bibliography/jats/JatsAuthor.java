package uk.co.terminological.bibliography.jats;

import java.net.URI;
import java.util.Optional;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

public class JatsAuthor implements Author, Raw<XmlElement> {

	private XmlElement raw;
	public JatsAuthor(XmlElement raw) {
		this.raw = raw;
	}

	public String getLastName() {
		try {
			return raw.doXpath("name/surname")
				.get(XmlElement.class)
				.flatMap(o -> o.getTextContent())
				.orElse("Unknown");
		} catch (XmlException e) {
			return "Unknown";
		}
	}
	public Optional<String> getFirstName() {
		try {
			return raw.doXpath("name/given-names")
				.get(XmlElement.class)
				.flatMap(o -> o.getTextContent());
		} catch (XmlException e) {
			return Optional.empty();
		}
	}
	public Optional<String> getInitials() {
		return getFirstName().map(s -> s.substring(0, 1));
	}
	public Optional<String> getORCID() {
		try {
			return raw.doXpath("./contrib-id[@contrib-id-type='orcid']")
				.get(XmlElement.class)
				.flatMap(o -> o.getTextContent());
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	public Stream<String> getAffiliations() {
		return raw.childElements("xref")
			.stream()
			.map(
				e -> e.getAttributeValue(
					"rel-id",
					URI.create("http://schema.highwire.org/Journal")
				)
			)
			.flatMap(
				id -> raw.up()
					.childElements("aff")
					.stream()
					.filter(
						e -> id.equals(
							e.getAttributeValue(
								"id",
								URI.create("http://schema.highwire.org/Journal")
							)
						)
					)
					.flatMap(
						o -> o.getTextContent()
							.stream()
					)
			);

	}

	public String toString() {
		return getLastName() + ", " + getInitials().orElse("Unknown");
	}
	@Override
	public XmlElement getRaw() { return raw; }

}