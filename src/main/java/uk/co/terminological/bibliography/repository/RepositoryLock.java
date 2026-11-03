package uk.co.terminological.bibliography.repository;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One writer per repository.
 *
 * SQLite's WAL makes concurrent <em>readers</em> safe and says nothing about two crawl engines: they would race on the
 * same records, checkpoint over each other's jobs, and spend two API budgets on one question. A server claims the
 * repository; anything that only reads does not need to.
 *
 * <h2>Why a file lock and not a file</h2>
 *
 * The lock is an OS file lock, so the operating system releases it when the process dies however it dies. A lock file
 * that is merely <em>present</em> would outlive a {@code kill -9} and leave the next start refusing a repository
 * nobody holds — which is worse than no lock, because the fix is undocumented and looks like corruption.
 *
 * The file still carries the holder's pid as text, because the point of refusing is to let a human find the server
 * they forgot about.
 */
class RepositoryLock {

	private static final Logger log = LoggerFactory.getLogger(RepositoryLock.class);

	private final Path path;
	private final FileChannel channel;
	private final FileLock lock;

	private RepositoryLock(Path path, FileChannel channel, FileLock lock) {
		this.path = path;
		this.channel = channel;
		this.lock = lock;
	}

	/**
	 * Claim the repository for writing, or refuse saying who holds it.
	 *
	 * @throws RepositoryException when another process holds it
	 */
	static RepositoryLock claim(Path repository) {
		var path = Path.of(repository.toAbsolutePath() + ".lock");
		FileChannel channel = null;
		try {
			channel = FileChannel.open(
				path, StandardOpenOption.CREATE, StandardOpenOption.READ,
				StandardOpenOption.WRITE
			);
			FileLock lock;
			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException e) {
				// this JVM already holds it: same answer as another process holding it
				lock = null;
			}
			if (lock == null) {
				var holder = readHolder(path);
				channel.close();
				throw new RepositoryException(
						"the repository at " + repository
								+ " is already open for writing by " + holder
								+ ". Stop that server, or point this one at another"
								+ " repository with repository-name."
				);
			}
			channel.truncate(0);
			channel.write(
				java.nio.ByteBuffer.wrap(
					(ProcessHandle.current()
						.pid() + System.lineSeparator())
							.getBytes(StandardCharsets.UTF_8)
				)
			);
			channel.force(true);
			return new RepositoryLock(path, channel, lock);
		} catch (RepositoryException e) {
			throw e;
		} catch (IOException e) {
			closeQuietly(channel);
			throw new RepositoryException(
					"could not lock the repository at " + repository, e
			);
		}
	}

	/** who the lock file says is holding it, in a form a human can act on */
	private static String readHolder(Path path) {
		try {
			var text = Files.readString(path)
				.trim();
			return text.isEmpty() ? "another process (pid unknown)"
				: "another process (pid " + text + ")";
		} catch (IOException e) {
			return "another process (pid unknown)";
		}
	}

	void release() {
		try {
			if (this.lock.isValid()) this.lock.release();
		} catch (IOException e) {
			log.warn("could not release the repository lock: {}", e.getMessage());
		}
		closeQuietly(this.channel);
		try {
			// tidy, not load-bearing: the lock is the OS lock, not the file's presence
			Files.deleteIfExists(this.path);
		} catch (IOException e) {
			log.debug("could not remove the lock file: {}", e.getMessage());
		}
	}

	private static void closeQuietly(FileChannel channel) {
		if (channel == null) return;
		try {
			channel.close();
		} catch (IOException e) {
			// nothing useful to do about a channel that will not close
		}
	}

}
