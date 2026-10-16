package uk.co.terminological.bibliography.api.zotero;

/**
 * A Zotero collection: a folder in the library. Sub-collections are expressed by `parentCollection`, and the library is
 * genuinely nested (16 top-level and 112 nested collections in the developer's library).
 *
 * [IMPLEMENTS](/design/features/feat-005-zotero-curated-repository-bridge.md)
 */
public class ZoteroCollection {

	private final String key;
	private final String name;
	private final String parentCollection;

	public ZoteroCollection(String key, String name, String parentCollection) {
		this.key = key;
		this.name = name;
		this.parentCollection = parentCollection;
	}

	public String getKey() { return key; }

	public String getName() { return name; }

	/** null for a top-level collection */
	public String getParentCollection() {
		return parentCollection;
	}

	public boolean isTopLevel() {
		return parentCollection == null || parentCollection.isEmpty();
	}
}
