package uk.co.terminological.bibliography.mcp;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.terminological.bibliography.record.Builder;
import uk.co.terminological.bibliography.record.CitationLink;
import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.MergedRecord;
import uk.co.terminological.bibliography.record.Record;
import uk.co.terminological.bibliography.record.RecordReferenceMapping;
import uk.co.terminological.bibliography.record.RecordWithCitations;
import uk.co.terminological.datatypes.FluentMap;
import uk.co.terminological.datatypes.FluentSet;
import uk.co.terminological.datatypes.Tuple;

/**
 * Stateful repository over the bibliographic clients: normalises DOIs, manages
 * a cross map of ids of different types, merges and deduplicates records,
 * caches a citation graph. (Salvaged from the rjavaapi Repository, minus the R
 * interface.) Long-lived; held in memory for the lifetime of an MCP server
 * session, persisted via save/load.
 */
public class Session implements Serializable {

	static Logger log = LoggerFactory.getLogger(Session.class);

	transient BiblioFacade facade;
	FluentMap<UUID, MergedRecord> records = new FluentMap<>();
	FluentMap<ImmutableRecordReference, UUID> ids = new FluentMap<>();
	Graph<ImmutableRecordReference, DefaultEdge> citations = new SimpleDirectedGraph<>(
			DefaultEdge.class
	);

	Set<ImmutableRecordReference> expandedUp = new HashSet<>();
	Set<ImmutableRecordReference> expandedDown = new HashSet<>();
	Set<ImmutableRecordReference> mapped = new HashSet<>();

	public Session(BiblioFacade facade) {
		this.facade = facade;
	}

	void setFacade(BiblioFacade facade) { this.facade = facade; }

	public void addAll(Collection<? extends Record> records) {
		records.forEach(this::add);
	}

	protected void add(Record record) {

		var uuid = this.merge(record.getIdentifiers());
		var toMerge = this.records.getOpt(uuid);
		var mr = toMerge.map(m -> m.merge(record))
			.orElse(MergedRecord.from(record));
		this.records.put(uuid, mr);
		// regardless of uuid stuff the bare ids can go in the citation graph. Duplicates will be resolved
		record.getIdentifiers()
			.forEach(r -> { this.citations.addVertex(r); });

		if (record instanceof RecordWithCitations) {
			((RecordWithCitations) record).getCitations()
				.forEach(this::add);
		}

	}

	protected void add(CitationLink citationLink) {
		if (!citationLink.isComplete()) return;
		var source = citationLink.getSource()
			.getIdentifier()
			.map(Builder::recordReference)
			.get();
		var target = citationLink.getTarget()
			.getIdentifier()
			.map(Builder::recordReference)
			.get();
		this.add(source);
		if (!target.equals(source)) {
			this.add(target);
			this.citations.addEdge(source, target);
		}
	}

	protected UUID merge(Collection<ImmutableRecordReference> ri) {
		List<UUID> uuids = ri.stream()
			.flatMap(this.ids::getStream)
			.collect(Collectors.toList());
		List<MergedRecord> toMerge = uuids.stream()
			.flatMap(this.records::getStream)
			.collect(Collectors.toList());
		if (uuids.size() != 1) {
			Optional<MergedRecord> merged = toMerge.stream()
				.collect(MergedRecord.collector());
			var newUuid = UUID.randomUUID();
			ri.forEach(i -> this.ids.put(i, newUuid));
			uuids.forEach(this.records::remove);
			merged.ifPresent(m -> this.records.put(newUuid, m));
			return newUuid;
		} else {
			ri.forEach(i -> this.ids.put(i, uuids.get(0)));
			return uuids.get(0);
		}

	}

	protected void add(RecordReferenceMapping mapping) {
		if (!mapping.isComplete()) return;
		var sourceRR = Builder.recordReference(mapping.getSource());
		var targetRR = Builder.recordReference(mapping.getTarget());
		var source = this.add(sourceRR);
		var target = this.add(targetRR);
		if (source.equals(target)) {
			// both present and matching UUIDs
		} else {
			this.merge(List.of(sourceRR, targetRR));
		}
	}

	protected UUID add(ImmutableRecordReference ri) {
		if (!this.ids.containsKey(ri)) {
			this.ids.put(ri, UUID.randomUUID());
			this.citations.addVertex(ri);
		}
		var uuid = this.ids.get(ri);
		return (uuid);
	}

	protected void remove(ImmutableRecordReference ri) {
		var uuid = this.ids.getOpt(ri);

		uuid.ifPresent(u -> {
			this.records.remove(u);
			List<ImmutableRecordReference> allRi = this.ids.entrySet()
				.stream()
				.filter(
					kv -> kv.getValue()
						.equals(u)
				)
				.map(Entry::getKey)
				.collect(Collectors.toList());
			allRi.forEach(ri2 -> {
				this.ids.remove(ri2);
				this.citations.removeVertex(ri2);
			});
		});

	}

	public Set<ImmutableRecordReference> retrievedIds() {
		return this.ids.entrySet()
			.stream()
			.filter(kv -> this.records.containsKey(kv.getValue()))
			.map(Entry::getKey)
			.collect(Collectors.toSet());
	}

	public Set<ImmutableRecordReference> missingIds() {
		return this.ids.entrySet()
			.stream()
			.filter(kv -> !this.records.containsKey(kv.getValue()))
			.map(Entry::getKey)
			.collect(Collectors.toSet());
	}

	public HashMap<UUID, Set<ImmutableRecordReference>> uuidToRef() {
		var out = new FluentMap<UUID, Set<ImmutableRecordReference>>();
		for (Entry<ImmutableRecordReference, UUID> kv : this.ids.entrySet()) {
			if (!out.containsKey(kv.getValue())) {
				out.put(kv.getValue(), FluentSet.empty());
			}
			out.get(kv.getValue())
				.add(kv.getKey());
		}
		return out;
	}

	public Map<UUID, MergedRecord> getRecords() { return this.records; }

	public Stream<Tuple<UUID, UUID>> uuidCitations() {
		return this.citations.edgeSet()
			.stream()
			.map(
				e -> Tuple.create(
					this.ids.get(this.citations.getEdgeSource(e)),
					this.ids.get(this.citations.getEdgeTarget(e))
				)
			)
			.distinct();
	}

	// Orchestration

	public Session collectSearch(String search) {
		Collection<? extends Record> hits = this.facade.search(search);
		hits.forEach(this::add);
		hits.stream()
			.filter(RecordWithCitations.class::isInstance)
			.map(h -> (RecordWithCitations) h)
			.flatMap(RecordWithCitations::getCitations)
			.forEach(this::add);
		return this;
	}

	public Session collectSearchByApi(HashMap<String, String> searchByApi) {
		Collection<? extends Record> hits = this.facade.multiSearch(
				searchByApi,
				this.facade.from,
				this.facade.to,
				this.facade.limit
		);
		hits.forEach(this::add);
		hits.stream()
			.filter(RecordWithCitations.class::isInstance)
			.map(h -> (RecordWithCitations) h)
			.flatMap(RecordWithCitations::getCitations)
			.forEach(this::add);
		return this;
	}

	public Session fetchMissingRecords() {
		this.facade.fetch(this.missingIds())
			.values()
			.stream()
			.forEach(this::add);
		return this;
	}

	public Session fetchRecords(Set<ImmutableRecordReference> refs) {
		this.facade.fetch(refs)
			.values()
			.forEach(this::add);
		return this;
	}

	public Session expandDownCitationGraph(int limit) {
		var toExpand = this.retrievedIds();
		toExpand.removeAll(this.expandedDown);
		if (toExpand.size() > limit) {
			log.error(
				"Exceeded limit size for expansion: was {}",
				toExpand.size()
			);
		} else {
			this.facade.citesReferences(toExpand)
				.stream()
				.forEach(this::add);
			this.expandedDown.addAll(toExpand);
		}
		return this;
	}

	public Session expandUpCitationGraph(int limit) {
		var toExpand = this.retrievedIds();
		toExpand.removeAll(this.expandedUp);
		if (toExpand.size() > limit) {
			log.error(
				"Exceeded limit size for expansion: was {}",
				toExpand.size()
			);
		} else {
			this.facade.referencesCiting(toExpand)
				.stream()
				.forEach(this::add);
			this.expandedUp.addAll(toExpand);
		}
		return this;
	}

	public Session removeRecords(Set<ImmutableRecordReference> refs) {
		refs.forEach(this::remove);
		return this;
	}

	public Session findAlternateIds() {
		Set<ImmutableRecordReference> toExpand = new HashSet<>(
				this.citations.vertexSet()
		);
		toExpand.removeAll(this.mapped);
		var tmp = this.facade.mappings(toExpand);
		tmp.forEach(this::add);
		log.debug(
			"added {} identifiers for {} original ids",
			tmp.size(),
			toExpand.size()
		);
		this.mapped.addAll(toExpand);
		return this;
	}

	public BiblioFacade getFacade() { return this.facade; }

	public String status() {
		return String.format(
			"Records: %s\nIds: %s (of which unique %s)\nCitation links: %s",
			this.records.size(),
			this.ids.size(),
			this.uuidToRef()
				.size(),
			this.citations.edgeSet()
				.size()
		);
	}

	public Session save(String filename) throws IOException {
		filename = filename.replaceFirst("^~", System.getProperty("user.home"));
		var f = new FileOutputStream(filename);
		ObjectOutput s = new ObjectOutputStream(f);
		s.writeObject(this);
		s.flush();
		s.close();
		return this;
	}

	public Session addSingle(IdType idType, String id) {
		this.facade.fetch(List.of(Builder.recordReference(idType, id)))
			.values()
			.forEach(this::add);
		return this;
	}

	// used by load via BiblioFacade.loadSession
	@SuppressWarnings("unused")
	private static Session load(String filename)
			throws IOException, ClassNotFoundException {
		filename = filename.replaceFirst("^~", System.getProperty("user.home"));
		var in = new FileInputStream(filename);
		var s = new ObjectInputStream(in);
		var out = (Session) s.readObject();
		in.close();
		return out;
	}

}
