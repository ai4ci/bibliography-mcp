package uk.co.terminological.bibliography.api.entrez;

import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.fluentxml.XmlElement;

public class EntrezMeshCode implements Raw<XmlElement> {
	private XmlElement raw;
	public EntrezMeshCode(XmlElement raw) {
		this.raw = raw;
	}
	public String getCode() { return raw.getAttributeValue("UI")
		.get(); }
	public String getTerm() { return raw.getTextContent()
		.get(); }
	public String toString() {
		return getCode() + ":" + getTerm();
	}
	@Override
	public XmlElement getRaw() { return raw; }
}