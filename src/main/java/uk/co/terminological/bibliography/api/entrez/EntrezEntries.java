package uk.co.terminological.bibliography.api.entrez;

import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

public class EntrezEntries implements Raw<XmlElement> {

	private XmlElement raw; // PubmedArticleSet

	public EntrezEntries(XmlElement raw) {
		this.raw = raw;
	}

	public Stream<EntrezEntry> stream() {
		if (raw == null) return Stream.empty();
		return raw.childElements("PubmedArticle")
			.stream()
			.map(o -> new EntrezEntry(o));

	}

	public Stream<String> getTitles() {
		if (raw == null) return Stream.empty();
		try {
			return this.raw.doXpath(".//ArticleTitle")
				.getManyAsStream(XmlElement.class)
				.flatMap(
					o -> o.getTextContent()
						.stream()
				);
		} catch (XmlException e) {
			return Stream.empty();
		}
	}

	public static EntrezEntries empty() {
		return new EntrezEntries(null);
	}

	@Override
	public XmlElement getRaw() { return raw; }

}