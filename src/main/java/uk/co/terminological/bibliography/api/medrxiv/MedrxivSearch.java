package uk.co.terminological.bibliography.api.medrxiv;

import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

public class MedrxivSearch implements Raw<XmlElement> {

	private XmlElement raw; // PubmedArticleSet

	public MedrxivSearch(XmlElement raw) {
		this.raw = raw;
	}

	public Stream<MedrxivSearchEntry> stream() {
		if (raw == null) return Stream.empty();
		try {
			return raw
				.doXpath(".//div[contains(@class,'highwire-article-citation')]")
				.getManyAsStream(XmlElement.class)
				.map(o -> new MedrxivSearchEntry(o));
		} catch (XmlException e) {
			return Stream.empty();
		}

	}

	public Stream<String> getTitles() {
		if (raw == null) return Stream.empty();
		try {
			return this.raw
				.doXpath(".//span[contains(@class,'highwire-cite-title')]")
				.getManyAsStream(XmlElement.class)
				.flatMap(
					o -> o.getTextContent()
						.stream()
				);
		} catch (XmlException e) {
			return Stream.empty();
		}
	}

	public static MedrxivSearch empty() {
		return new MedrxivSearch(null);
	}

	@Override
	public XmlElement getRaw() { return raw; }

}