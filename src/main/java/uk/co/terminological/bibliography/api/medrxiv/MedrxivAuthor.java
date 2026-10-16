package uk.co.terminological.bibliography.api.medrxiv;

import java.util.Optional;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

public class MedrxivAuthor implements Author, Raw<XmlElement> {

	private XmlElement raw;

	public MedrxivAuthor(XmlElement raw) {
		this.raw = raw;
	}

	@Override
	public XmlElement getRaw() { return raw; }

	@Override
	public Optional<String> getORCID() { return Optional.empty(); }

	@Override
	public Optional<String> getFirstName() {
		try {
			return raw.doXpath("./span[contains(@class,'nlm-given-names')]")
				.getOne(XmlElement.class)
				.getTextContent();
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	@Override
	public String getLastName() {
		try {
			return raw.doXpath("./span[contains(@class,'nlm-surname')]")
				.getOne(XmlElement.class)
				.getTextContent()
				.orElse("Unknown");
		} catch (XmlException e) {
			return "Unknown";
		}
	}

	@Override
	public Optional<String> getInitials() { return Optional.empty(); }

	@Override
	public Stream<String> getAffiliations() { return Stream.empty(); }

}
