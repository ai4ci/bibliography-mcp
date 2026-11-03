package uk.co.terminological.bibliography.api.zotero;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Which part of a Zotero library writes are confined to.
 *
 * {@code zotero-write-collection} began as a single collection key, which is precise and awkward: it names one
 * collection, and a human who thinks of their library as "everything under api-" has to look a key up to say so. A
 * pattern describes a <em>region</em> of the collection tree instead, matched against a collection's name or its key.
 *
 * <h2>Two different questions</h2>
 *
 * <strong>May this write happen?</strong> Yes if the collection, or any collection above it, is in the region. A
 * sub-collection the bridge creates is a child of the target and keeps a name of its own, so inheritance is what keeps
 * it writable.
 *
 * <strong>Where does a new sub-collection go?</strong> Under the region's root — the matching collection that is not
 * itself inside another match. A pattern matching four collections where three are nested inside the fourth has one
 * root and is unambiguous; a pattern matching two unrelated collections has two, and choosing between them is the
 * developer's decision rather than this code's.
 *
 * <h2>Globs, not regular expressions</h2>
 *
 * {@code *} and {@code ?}, matched without case. Everything else is literal, because a collection called
 * {@code Smith (2024)} is a name and not a pattern, and reading it as one would quietly widen what a write may touch.
 */
public class CollectionScope {

	private CollectionScope() {}

	/** Whether one name or key is in the region a pattern describes. */
	public static boolean matches(String pattern, String nameOrKey) {
		if (pattern == null || pattern.isBlank() || nameOrKey == null)
			return false;
		return glob(pattern).matcher(nameOrKey.trim())
			.matches();
	}

	private static Pattern glob(String pattern) {
		var out = new StringBuilder();
		for (char c : pattern.trim()
			.toCharArray()) {
			if (c == '*') out.append(".*");
			else if (c == '?') out.append('.');
			else out.append(Pattern.quote(String.valueOf(c)));
		}
		return Pattern.compile(
			out.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
		);
	}

	private static boolean inRegion(
			String pattern, ZoteroCollection collection
	) {
		return matches(pattern, collection.getName())
				|| matches(pattern, collection.getKey());
	}

	/**
	 * Whether a write to this collection is allowed.
	 *
	 * A collection the library does not contain is refused: a key that names nothing cannot be shown to be inside the
	 * region, and a guard that lets the unknown through is not a guard.
	 */
	public static boolean permits(
			String pattern, String collectionKey, List<ZoteroCollection> library
	) {
		if (pattern == null || pattern.isBlank() || collectionKey == null)
			return false;
		Map<String, ZoteroCollection> byKey = byKey(library);
		var current = byKey.get(collectionKey);
		// twenty hops is deeper than any real library and stops a parent cycle from
		// becoming an infinite loop
		for (int hops = 0; hops < 20 && current != null; hops++) {
			if (inRegion(pattern, current)) return true;
			var parent = current.getParentCollection();
			current = parent == null ? null : byKey.get(parent);
		}
		return false;
	}

	/**
	 * The tops of the region: matching collections that are not inside another match.
	 *
	 * This is what a new sub-collection hangs off, and why a pattern matching a whole nested family is still a single
	 * unambiguous target.
	 */
	public static List<ZoteroCollection> roots(
			String pattern, List<ZoteroCollection> library
	) {
		Map<String, ZoteroCollection> byKey = byKey(library);
		List<ZoteroCollection> out = new ArrayList<>();
		for (ZoteroCollection collection : library) {
			if (!inRegion(pattern, collection)) continue;
			if (!hasMatchingAncestor(pattern, collection, byKey))
				out.add(collection);
		}
		return out;
	}

	private static boolean hasMatchingAncestor(
			String pattern, ZoteroCollection collection,
			Map<String, ZoteroCollection> byKey
	) {
		var parentKey = collection.getParentCollection();
		for (int hops = 0; hops < 20 && parentKey != null; hops++) {
			var parent = byKey.get(parentKey);
			if (parent == null) return false;
			if (inRegion(pattern, parent)) return true;
			parentKey = parent.getParentCollection();
		}
		return false;
	}

	/**
	 * The one collection a new sub-collection belongs under, or a refusal that says why not.
	 *
	 * Both failures name what they saw: a pattern matching nothing is usually a typo or a collection that has not been
	 * created yet, and a pattern matching two unrelated collections needs a decision this code is not entitled to make.
	 */
	public static ZoteroCollection requireSingleRoot(
			String pattern, List<ZoteroCollection> library
	) {
		var roots = roots(pattern, library);
		if (roots.isEmpty()) throw new IllegalStateException(
				"no collection matches zotero-write-collection '" + pattern
						+ "'; create it in Zotero, or point the property at one that exists"
		);
		if (roots.size() > 1) throw new IllegalStateException(
				"zotero-write-collection '" + pattern
						+ "' matches more than one unrelated collection ("
						+ roots.stream()
							.map(c -> c.getName() + " [" + c.getKey() + "]")
							.collect(Collectors.joining(", "))
						+ "); narrow the pattern, or name one of their keys"
		);
		return roots.get(0);
	}

	private static Map<String, ZoteroCollection> byKey(
			List<ZoteroCollection> library
	) {
		Map<String, ZoteroCollection> out = new LinkedHashMap<>();
		for (ZoteroCollection collection : library)
			out.put(collection.getKey(), collection);
		return out;
	}

}
