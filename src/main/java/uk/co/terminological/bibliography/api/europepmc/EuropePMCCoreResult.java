package uk.co.terminological.bibliography.api.europepmc;

import static uk.co.terminological.bibliography.record.Builder.recordReference;
import static uk.co.terminological.datatypes.StreamExceptions.ignoreFunction;
import static uk.co.terminological.datatypes.StreamExceptions.ignoreRuntime;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import uk.co.terminological.bibliography.ExtensibleJson;
import uk.co.terminological.bibliography.record.Author;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.PrintRecord;
import uk.co.terminological.bibliography.record.Provenance;
import uk.co.terminological.bibliography.record.RecordReference;

public class EuropePMCCoreResult extends EuropePMCLiteResult
		implements PrintRecord {

	// https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=DOI:10.1073/pnas.0506580102&sort=CITED%20desc&format=json&resultType=core
	/*
	 * authorList author fullName firstName lastName initials authorId affiliation affiliationOrgId
	 */

	@Override
	public Provenance getProvenance() {
		return Provenance
			.canonical(Provenance.EUROPEPMC, this.getNativePubTypes());
	}

	public EuropePMCCoreResult(JsonNode node) {
		super(node);
	}

	@Override
	public List<RecordReference> getOtherIdentifiers() {
		List<RecordReference> tmp = new ArrayList<>();
		this.getDOI()
			.map(d -> recordReference(IdType.DOI, d))
			.ifPresent(tmp::add);
		this.getPMCID()
			.map(d -> recordReference(IdType.PMCID, d))
			.ifPresent(tmp::add);
		this.getPMID()
			.map(d -> recordReference(IdType.PMID, d))
			.ifPresent(tmp::add);
		this.getIdentifier()
			.map(d -> recordReference(this.getIdentifierType(), d))
			.ifPresent(tmp::add);
		return tmp;
	}

	@Override
	public List<Author> getAuthors() {
		return this.streamPath(EuropePMCAuthor.class, "authorList", "author")
			.collect(Collectors.toList());
	}

	@Override
	public Optional<String> getJournal() {
		return this.streamPath("journalInfo", "journal", "title")
			.map(ExtensibleJson::asString)
			.findFirst();
	}

	@Override
	public Stream<String> getLicenses() {
		return this.asString("license")
			.stream();
	}

	@Override
	public Optional<String> getAbstract() {
		return this.asString("abstractText");
	}

	@Override
	public Optional<LocalDate> getDate() {
		return this.asString("firstPublicationDate")
			.flatMap(ignoreFunction(LocalDate::parse));
	}

	@Override
	public Optional<URI> getPdfUri() {
		return this.streamPath("fullTextUrlList", "fullTextUrl")
			.filter(
				n -> n.asString("documentStyle")
					.orElse("XXX")
					.equalsIgnoreCase("PDF")
			)
			.flatMap(
				n -> n.asString("url")
					.stream()
			)
			.filter(n -> n != null)
			.findFirst()
			.flatMap(ignoreRuntime(URI::create));

	}

}
