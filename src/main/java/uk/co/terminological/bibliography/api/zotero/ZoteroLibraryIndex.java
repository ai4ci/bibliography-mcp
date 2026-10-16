package uk.co.terminological.bibliography.api.zotero;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import uk.co.terminological.bibliography.record.IdType;
import uk.co.terminological.bibliography.record.Record;

/**
 * Whether a record discovered from an API is already in the library.
 *
 * Built by holding one match key per item: DOI, ISBN, and title+year. The last one matters: only 52.6% of the
 * developer's library carries a DOI and 30.8% has no DOI, ISBN or URL at all, so a DOI-only index would report half the
 * library as absent and invite duplicate imports.
 *
 * Matching is exact on normalised values ({@link ZoteroMatching}) — see the write rules in feat-005 for what a caller
 * should do when it *does* match, and for why a near miss is not treated as a match.
 *
 * [IMPLEMENTS](/design/features/feat-005-zotero-curated-repository-bridge.md)
 */
public class ZoteroLibraryIndex {

	private final Map<String, String> byDoi = new HashMap<>();
	private final Map<String, String> byIsbn = new HashMap<>();
	private final Map<String, String> byTitleYear = new HashMap<>();
	private final Set<String> distinctKeys = new LinkedHashSet<>();
	private final String serverId;
	private final long libraryVersion;

	private ZoteroLibraryIndex(String serverId, long libraryVersion) {
		this.serverId = serverId;
		this.libraryVersion = libraryVersion;
	}

	public static ZoteroLibraryIndex of(
			Collection<ZoteroItem> items, String serverId, long libraryVersion
	) {
		ZoteroLibraryIndex out = new ZoteroLibraryIndex(serverId, libraryVersion);
		for (ZoteroItem item : items) {
			String key = item.getKey();
			if (key == null) continue;
			out.distinctKeys.add(key);
			item.getIdentifier(IdType.DOI)
				.map(ZoteroMatching::doi)
				.ifPresent(v -> out.byDoi.putIfAbsent(v, key));
			item.getIdentifier(IdType.ISBN)
				.map(ZoteroMatching::isbn)
				.ifPresent(v -> out.byIsbn.putIfAbsent(v, key));
			Optional<String> titleYear = item.getTitle()
				.map(
					t -> ZoteroMatching.titleYear(
						t,
						item.getDate()
							.map(LocalDate::getYear)
							.orElse(null)
					)
				)
				.filter(java.util.Objects::nonNull);
			titleYear.ifPresent(v -> out.byTitleYear.putIfAbsent(v, key));
		}
		return out;
	}

	/** DOI first (most reliable), then ISBN, then title+year. */
	public Optional<String> findKey(Record record) {
		if (record == null) return Optional.empty();
		Optional<String> byId = record.getIdentifier(IdType.DOI)
			.map(ZoteroMatching::doi)
			.flatMap(v -> Optional.ofNullable(byDoi.get(v)));
		if (byId.isPresent()) return byId;
		Optional<String> byIsbnMatch = record.getIdentifier(IdType.ISBN)
			.map(ZoteroMatching::isbn)
			.flatMap(v -> Optional.ofNullable(byIsbn.get(v)));
		if (byIsbnMatch.isPresent()) return byIsbnMatch;
		// no identifier, or an identifier we hold nothing for: fall back to title+year
		return record.getTitle()
			.map(
				t -> ZoteroMatching.titleYear(
					t,
					record.getDate()
						.map(LocalDate::getYear)
						.orElse(null)
				)
			)
			.filter(java.util.Objects::nonNull)
			.flatMap(v -> Optional.ofNullable(byTitleYear.get(v)));
	}

	/** items indexed (an item may be reachable by more than one key) */
	public int size() {
		return distinctKeys.size();
	}

	public String getServerId() { return serverId; }

	public long getLibraryVersion() { return libraryVersion; }
}
