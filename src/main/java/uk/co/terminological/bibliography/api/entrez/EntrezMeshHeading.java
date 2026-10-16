package uk.co.terminological.bibliography.api.entrez;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.fluentxml.XmlElement;

public class EntrezMeshHeading implements Raw<XmlElement> {
	private XmlElement raw;
	public EntrezMeshHeading(XmlElement raw) {
		this.raw = raw;
	}
	public EntrezMeshCode getDescriptor() {
		return raw.childElements("DescriptorName")
			.stream()
			.findFirst()
			.map(o -> new EntrezMeshCode(o))
			.get();
	}
	public Stream<EntrezMeshCode> getQualifiers() {
		return raw.childElements("QualifierName")
			.stream()
			.map(o -> new EntrezMeshCode(o));
	}
	public String toString() {
		return getDescriptor().toString() + " ["
				+ getQualifiers().map(q -> q.toString())
					.collect(Collectors.joining("; "))
				+ "]";
	}
	@Override
	public XmlElement getRaw() { return raw; }
}