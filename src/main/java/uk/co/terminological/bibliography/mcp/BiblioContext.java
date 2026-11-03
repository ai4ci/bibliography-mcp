package uk.co.terminological.bibliography.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import uk.co.terminological.bibliography.api.BiblioFacade;
import uk.co.terminological.bibliography.api.zotero.ZoteroClient;
import uk.co.terminological.bibliography.crawl.CrawlEngine;
import uk.co.terminological.bibliography.repository.RepositoryStore;

/**
 * What the tools act on: one repository, one facade, one crawl engine, and Zotero if it is there.
 *
 * Held for the life of the server rather than per request, because that is what the server now is — a service with a
 * durable repository and a crawl that outlives any one call, rather than a process a client starts for a conversation.
 */
public class BiblioContext implements AutoCloseable {

	private final RepositoryStore store;
	private final BiblioFacade facade;
	private final CrawlEngine crawl;
	private final ZoteroClient zotero;
	private final java.nio.file.Path pdfDirectory;
	private final ObjectMapper mapper = new ObjectMapper()
		.registerModule(new Jdk8Module());

	public BiblioContext(
			RepositoryStore store, BiblioFacade facade, ZoteroClient zotero,
			java.nio.file.Path pdfDirectory
	) {
		this.store = store;
		this.facade = facade;
		this.zotero = zotero;
		this.pdfDirectory = pdfDirectory;
		this.crawl = new CrawlEngine(store, facade);
	}

	public RepositoryStore store() { return this.store; }

	/**
	 * Where downloaded PDFs land: a sibling of the repository, per ADR-005.
	 *
	 * A plain directory on purpose — Zotero reads a file from it, and so can anything that indexes PDF content,
	 * neither of which should need to know this project exists.
	 */
	public java.nio.file.Path pdfDirectory() { return this.pdfDirectory; }
	public BiblioFacade facade() { return this.facade; }
	public CrawlEngine crawl() { return this.crawl; }
	public ObjectMapper mapper() { return this.mapper; }

	/**
	 * The Zotero bridge, or a refusal that says what to do about it.
	 *
	 * Zotero is optional and checked when it is used rather than at startup: a library that is not running must stop
	 * the sync, not the server, and everything else — search, crawl, rank, select — works without it.
	 */
	public ZoteroClient requireZotero() {
		if (this.zotero == null) throw new IllegalStateException(
				"Zotero support is switched off (zotero-mode=off in the properties file)"
		);
		this.zotero.checkAvailable();
		return this.zotero;
	}

	public boolean hasZotero() { return this.zotero != null; }

	@Override
	public void close() {
		this.crawl.close();
		this.store.close();
	}

}
