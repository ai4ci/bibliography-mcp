package uk.co.terminological.bibliography.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jgrapht.alg.scoring.PageRank;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;

import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.MergedRecord;
import uk.co.terminological.datatypes.Tuple;
import uk.co.terminological.nlptools.Corpus;
import uk.co.terminological.nlptools.Filter;
import uk.co.terminological.nlptools.TopicModelBuilder;
import uk.co.terminological.nlptools.TopicModelResult;

/**
 * Analysis over a Session: page rank over the uuid citation graph, and topic
 * modelling over the session records using nlp-tools. (Salvaged from the
 * rjavaapi CitationAnalyser and TopicModelAnalyser, minus the R interface.)
 */
public class Analysis {

	/**
	 * Page rank over the citation graph, keyed by record uuid.
	 */
	public static Map<UUID, Double> pageRank(Session session) {
		var citations = new SimpleDirectedGraph<UUID, DefaultEdge>(DefaultEdge.class);
		session.uuidCitations().forEach(t -> {
			citations.addVertex(t.getFirst());
			if (!t.getFirst().equals(t.getSecond())) {
				citations.addVertex(t.getSecond());
				citations.addEdge(t.getFirst(), t.getSecond());
			}
		});
		PageRank<UUID, DefaultEdge> pr = new PageRank<>(citations);
		return pr.getScores();
	}

	/**
	 * Page rank joined with titles for MCP output.
	 */
	public static List<Map<String, Object>> pageRankWithTitles(Session session) {
		var records = session.records;
		return pageRank(session).entrySet()
			.stream()
			.map(e -> {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("uuid", e.getKey()
					.toString());
				row.put("pageRank", e.getValue());
				row.put("title", records.getOpt(e.getKey())
					.flatMap(MergedRecord::getTitle)
					.orElse(null));
				return row;
			})
			.collect(Collectors.toList());
	}

	/**
	 * Topic model over the session records (title + abstract), returning rows
	 * of {topic, uuid, score} for each document-topic weight.
	 */
	public static List<Map<String, Object>> topicModel(
			Session session, List<String> stopwords,
			int topics, double alpha, double beta
	) {
		// ponytail: rebuilds corpus and model on every call; LDA is slow on
		// large sessions — cache TopicModelResult in the session if that matters
		TopicModelResult result;
		var texts = Corpus.create()
			.withStopwordFilter(stopwords)
			.withTokenFilter(Filter.number())
			.withTokenFilter(Filter.shorterThan(4));

		session.records.forEach((k, r) -> {
			var nodeId = k.toString();
			var title = r.getTitle()
				.orElse("");
			var abstrct = r.getAbstract()
				.orElse("");
			var doc = texts.addDocument(
				nodeId,
				title + (abstrct != null ? "\n" + abstrct : "")
			);

			r.getIdentifier(IdType.DOI)
				.ifPresent(d -> doc.addMetadata("DOI", d));
			r.getIdentifier(IdType.PMCID)
				.ifPresent(d -> doc.addMetadata("PMCID", d));
			r.getIdentifier(IdType.PMID)
				.ifPresent(d -> doc.addMetadata("PMID", d));
		});

		result = TopicModelBuilder.create(texts)
			.withTopics(topics)
			.execute(alpha, beta);

		return result.getTopicsForDocuments()
			.flatMap(
				t -> t.streamDocuments()
					.map(
						wd -> {
							Map<String, Object> row = new LinkedHashMap<>();
							row.put("topic", t.getTopicId());
							row.put("uuid", wd.getTarget()
								.getIdentifier());
							row.put("score", wd.getWeight());
							return row;
						}
					)
			)
			.collect(Collectors.toList());
	}

}
