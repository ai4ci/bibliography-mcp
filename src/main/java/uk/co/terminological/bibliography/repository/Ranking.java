package uk.co.terminological.bibliography.repository;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jgrapht.alg.scoring.PageRank;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;

import uk.co.terminological.bibliography.record.ImmutableRecordReference;
import uk.co.terminological.bibliography.record.RecordReference;

/**
 * Which works in a repository matter, by the shape of its citation graph.
 *
 * PageRank over the edges the crawl collected, keyed by identifier rather than by a session-local uuid, so a score
 * survives the process that computed it and can be written back to the store.
 *
 * It answers "central in this corpus", not "important in the literature": a crawl sees the graph it fetched, and a
 * work cited by three of the twenty-five seeds outranks one cited by thousands of papers nobody crawled. That is the
 * useful question when choosing what to read next out of a crawl, and the misleading one if read as esteem.
 */
public class Ranking {

	private Ranking() {}

	public static Map<ImmutableRecordReference, Double> pageRank(
			RepositoryStore store
	) {
		var graph = new SimpleDirectedGraph<ImmutableRecordReference, DefaultEdge>(
				DefaultEdge.class
		);
		store.citations()
			.forEach(link -> {
				var source = link.getSource()
					.getIdentifier()
					.map(RecordReference::asId);
				var target = link.getTarget()
					.getIdentifier()
					.map(RecordReference::asId);
				if (source.isEmpty() || target.isEmpty()) return;
				graph.addVertex(source.get());
				graph.addVertex(target.get());
				// a self-citation is a loop a SimpleDirectedGraph will not take, and
				// contributes nothing to a ranking anyway
				if (!source.get()
					.equals(target.get()))
					graph.addEdge(source.get(), target.get());
			});
		if (graph.vertexSet()
			.isEmpty()) return new LinkedHashMap<>();
		return new PageRank<>(graph).getScores();
	}

}
