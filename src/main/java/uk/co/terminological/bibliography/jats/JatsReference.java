package uk.co.terminological.bibliography.jats;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.Raw;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReference;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

public class JatsReference implements RecordReference, Record, Raw<XmlElement> {

	private XmlElement raw;
	public JatsReference(XmlElement raw) {this.raw = raw;}
	@Override
	public XmlElement getRaw() {
		return raw;
	}
	
	@Override
	public Optional<String> getIdentifier() {
		try {
			return raw.doXpath("pub-id[@pub-id-type='doi']")
					.get(XmlElement.class).flatMap(o -> o.getTextContent());
		} catch (XmlException e) {
			return Optional.empty();
		}
	}

	@Override
	public IdType getIdentifierType() {
		return IdType.DOI;
	}

	@Override
	public List<? extends RecordReference> getOtherIdentifiers() {
		List<ImmutableRecordReference> out = new ArrayList<>();
		childWithAttribute("pub-id", "pub-id-type", "pmid")
				.ifPresent(id -> out.add(Builder.recordReference(IdType.PMID, id)));
		childWithAttribute("pub-id", "pub-id-type", "pmcid")
				.ifPresent(id -> out.add(Builder.recordReference(IdType.PMCID, id)));
		return out;
	}

	@Override
	public List<? extends Author> getAuthors() {
		return raw
				.childElements("string-name")
				.stream()
				.map(o -> new JatsCitedAuthor(o)).collect(Collectors.toList());
	}

	@Override
	public Stream<String> getLicenses() {
		return Stream.empty();
	}

	@Override
	public Optional<String> getAbstract() {
		return Optional.empty();
	}

	/**
	 * A reference is named by its article title, or — for the grey literature, reports
	 * and web pages — by the collaborative name the publisher puts in `collab`, or
	 * failing that by the source it appeared in.
	 *
	 * This used to be gated on `publication-type == "journal"`: a string identity
	 * comparison, which is false for text that is not the interned literal, so in
	 * practice **no** reference had a title.
	 */
	/** the text of the first child element with this name, if there is one */
	private Optional<String> firstChildText(String name) {
		return raw.childElements(name).findFirst().flatMap(el -> el.getTextContent());
	}

	/** the text of the first child element carrying a particular attribute value */
	private Optional<String> childWithAttribute(String element, String attribute, String value) {
		return raw.childElements(element)
				.stream()
				.filter(el -> el.getAttributeValue(attribute).map(value::equals).orElse(false))
				.findFirst()
				.flatMap(el -> el.getTextContent());
	}

	@Override
	public Optional<String> getTitle() {
		return firstChildText("article-title")
				.or(() -> firstChildText("collab"))
				.or(() -> firstChildText("source"));
	}

	@Override
	public Optional<String> getJournal() {
		return raw
		.childElements("source")
		.findFirst().flatMap(el -> el.getTextContent());
	}

	/**
	 * A year may be absent, or not a number at all ("in press", "2020-2021"). This
	 * project returns Optionals rather than throwing, so an unusable year is simply
	 * no date.
	 */
	@Override
	public Optional<LocalDate> getDate() {
		Optional<Integer> year = number(firstChildText("year"));
		if (!year.isPresent()) return Optional.empty();
		return Optional.of(LocalDate.of(
				year.get(),
				number(firstChildText("month")).orElse(1),
				number(firstChildText("day")).orElse(1)));
	}

	private static Optional<Integer> number(Optional<String> text) {
		return text.map(String::trim)
				.flatMap(value -> {
					try {
						return value.isEmpty() ? Optional.empty() : Optional.of(Integer.parseInt(value));
					} catch (NumberFormatException e) {
						return Optional.empty();
					}
				});
	}

	@Override
	public Optional<URI> getPdfUri() {
		return Optional.empty();
	}
	

}
