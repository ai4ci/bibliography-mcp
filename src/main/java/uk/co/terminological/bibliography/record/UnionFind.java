package uk.co.terminological.bibliography.record;

import java.util.*;

/**
 * Tracks equivalence classes of identifiers — two identifiers are equivalent
 * when an IdMapper found a mapping between them (DOI→PMID, PMID→PMCID, etc.).
 *
 * @param <T> the identifier reference type (typically {@link ImmutableRecordReference})
 */
public class UnionFind<T> {

	private final Map<T, T> parent = new HashMap<>();

	/** Ensure {@code x} has an entry. No-op if already present. */
	public void add(T x) {
		parent.putIfAbsent(x, x);
	}

	/**
	 * Find the canonical root of {@code x}'s equivalence class.
	 * @return the root, or empty if {@code x} was never {@link #add added}
	 */
	public Optional<T> find(T x) {
		var p = parent.get(x);
		if (p == null) return Optional.empty();
		if (!p.equals(x)) {
			// path compression
			parent.put(x, findOrSelf(p));
		}
		return Optional.ofNullable(parent.get(x));
	}

	private T findOrSelf(T x) {
		return find(x).orElse(x);
	}

	/** Declare {@code x} and {@code y} equivalent. */
	public void union(T x, T y) {
		add(x);
		add(y);
		var rx = findOrSelf(x);
		var ry = findOrSelf(y);
		if (!rx.equals(ry)) parent.put(ry, rx);
	}

	/**
	 * All identifiers grouped by equivalence-class root.
	 * The map preserves insertion order of the first root per group.
	 */
	public Map<T, Set<T>> groups() {
		Map<T, Set<T>> out = new LinkedHashMap<>();
		parent.keySet()
			.forEach(k -> {
				var r = findOrSelf(k);
				out.computeIfAbsent(r, x -> new LinkedHashSet<>())
					.add(k);
			});
		return out;
	}
}
