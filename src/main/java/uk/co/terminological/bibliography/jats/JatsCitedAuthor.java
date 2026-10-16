package uk.co.terminological.bibliography.jats;

import java.util.Optional;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

public class JatsCitedAuthor implements Author, Raw<XmlElement> {

	private XmlElement raw;
	public JatsCitedAuthor(XmlElement raw) {
		this.raw = raw;
	}

	@Override
	public XmlElement getRaw() { return raw; }

	@Override
	public Optional<String> getORCID() { return Optional.empty(); }

	@Override
	public Optional<String> getFirstName() {
		try {
			return raw.doXpath("given-names")
				.get(XmlElement.class)
				.flatMap(o -> o.getTextContent());
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	@Override
	public String getLastName() {
		try {
			return raw.doXpath("surname")
				.get(XmlElement.class)
				.flatMap(o -> o.getTextContent())
				.orElse("Unknown");
		} catch (XmlException e) {
			return "Unknown";
		}
	}

	@Override
	public Optional<String> getInitials() {
		return getFirstName().map(s -> s.substring(0, 1));
	}

	@Override
	public Stream<String> getAffiliations() { return Stream.empty(); }

}
