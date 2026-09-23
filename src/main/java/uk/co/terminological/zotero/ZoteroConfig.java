package uk.co.terminological.zotero;

import java.util.Properties;

/**
 * Zotero settings, read from the same properties file as the API credentials.
 *
 * Defaults are least-privilege: local mode, and writes off with no target
 * collection, so a misconfiguration cannot write to the library.
 *
 * [IMPLEMENTS](/design/external-interfaces/interface-002-configuration-and-secrets.md)
 * [IMPLEMENTS](/design/features/feat-005-zotero-curated-repository-bridge.md)
 */
public class ZoteroConfig {

	public static final String DEFAULT_BASE_URL = "http://localhost:23119/api";
	public static final String APP_NAME = "bibliographic-api-client";

	private final boolean enabled;
	private final String baseUrl;
	private final String userId;
	private final boolean allowWrites;
	private final String writeCollection;

	private ZoteroConfig(boolean enabled, String baseUrl, String userId, boolean allowWrites, String writeCollection) {
		this.enabled = enabled;
		this.baseUrl = baseUrl;
		this.userId = userId;
		this.allowWrites = allowWrites;
		this.writeCollection = writeCollection;
	}

	public static ZoteroConfig from(Properties properties) {
		Properties p = properties == null ? new Properties() : properties;
		String mode = p.getProperty("zotero-mode", "local").trim().toLowerCase();
		boolean enabled = !mode.equals("off");
		String baseUrl = p.getProperty("zotero-base-url", DEFAULT_BASE_URL).trim();
		String userId = p.getProperty("zotero-user-id", "0").trim();
		boolean allowWrites = Boolean.parseBoolean(p.getProperty("zotero-allow-writes", "false").trim());
		String writeCollection = p.getProperty("zotero-write-collection", "").trim();
		return new ZoteroConfig(enabled, baseUrl, userId, allowWrites, writeCollection);
	}

	/** Zotero switched off entirely: the Zotero tools report that rather than failing */
	public static ZoteroConfig disabled() {
		return new ZoteroConfig(false, DEFAULT_BASE_URL, "0", false, "");
	}

	public boolean isEnabled() { return enabled; }

	public String getBaseUrl() { return baseUrl; }

	/** 0 means "the locally logged-in user", which the local API accepts */
	public String getUserId() { return userId; }

	public boolean isAllowWrites() { return allowWrites; }

	/** the only collection writes may touch, or empty when writes are not configured */
	public String getWriteCollection() { return writeCollection; }

	public boolean hasWriteTarget() { return allowWrites && !writeCollection.isEmpty(); }
}
