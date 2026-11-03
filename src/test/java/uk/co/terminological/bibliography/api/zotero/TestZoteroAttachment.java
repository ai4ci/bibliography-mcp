package uk.co.terminological.bibliography.api.zotero;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * The attachment item Zotero is asked to create, and the refusals around it.
 *
 * The protocol itself — four local calls, POST rather than PUT on the upload url — is proven live in
 * prototype-003 and exercised by {@code TestZoteroBridgeLive}. What is asserted here is the part that must not depend
 * on a running Zotero: the shape of the item, and that nothing is uploaded that should not be.
 *
 * [TESTS](/design/test-scripts/test-003-zotero-bridge-tests.md)
 */
public class TestZoteroAttachment {

	private static Path aPdf() throws Exception {
		var file = Files.createTempFile("attach", ".pdf");
		Files.write(file, "%PDF-1.4\nsmall but real\n%%EOF".getBytes());
		return file;
	}

	@Test
	public void theAttachmentNamesItsParentAndItsFile() throws Exception {
		var file = aPdf();

		var item = ZoteroItemWriter.toAttachment("PARENT01", file);

		assertEquals("attachment", item.get("itemType")
			.asText());
		// imported_file is the mode that means "Zotero holds the bytes", as opposed
		// to linked_file, which would leave a reference to a path the library cannot
		// rely on
		assertEquals("imported_file", item.get("linkMode")
			.asText());
		assertEquals("PARENT01", item.get("parentItem")
			.asText());
		assertEquals("application/pdf", item.get("contentType")
			.asText());
		assertEquals(
			file.getFileName()
				.toString(),
			item.get("filename")
				.asText()
		);
		// md5 and mtime are null at creation and filled in by the upload: sending a
		// hash before the bytes exist is what Zotero refuses
		assertTrue(item.get("md5")
			.isNull());
		assertTrue(item.get("mtime")
			.isNull());
	}

	@Test
	public void anAttachmentIsNeverPutInACollection() throws Exception {
		var item = ZoteroItemWriter.toAttachment("PARENT01", aPdf());

		// Zotero refuses a child attachment that names a collection; it belongs to
		// its parent, and the parent is what sits in the collection
		assertFalse(item.toString(), item.has("collections"));
	}

	@Test
	public void aMissingFileIsRefusedBeforeAnythingIsCreated() {
		try {
			ZoteroItemWriter.toAttachment("PARENT01", Path.of("/nowhere/x.pdf"));
			fail("a file that does not exist should not become an attachment");
		} catch (IllegalArgumentException e) {
			assertTrue(
				e.getMessage(), e.getMessage()
					.contains("x.pdf")
			);
		}
	}

}
