package io.jenkins.jenkinsfile.runner.bootstrap.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.LongSupplier;

/**
 * Downloads remote artifacts into the local Jenkinsfile Runner cache.
 *
 * <p>The data is first written to a temporary file in the same directory as the destination,
 * and it is moved to the final name only once the whole stream has been read and, when the
 * expected size is known, verified against the number of bytes actually written. As a result
 * other Jenkinsfile Runner instances sharing the cache never observe a partially downloaded
 * file at the final path: a concurrent {@code exists()} check either finds the previous,
 * untouched file or nothing at all.</p>
 *
 * <p>If anything goes wrong while downloading, the temporary file is deleted and the original
 * exception is rethrown. The destination path, including any file that was there before the
 * attempt, is left untouched.</p>
 *
 * <p>The move is performed with {@link StandardCopyOption#ATOMIC_MOVE} when the file system
 * supports it. On {@link AtomicMoveNotSupportedException} it falls back to a plain move with
 * {@link StandardCopyOption#REPLACE_EXISTING}; on such file systems the single-instance
 * guarantee (no partial file at the final path) still holds, only the replacement itself may
 * not be atomic with respect to other processes.</p>
 *
 * <p>Concurrent downloads of the same destination are not serialized: every caller writes its
 * own uniquely named temporary file and the last successful move wins. Cache entries are
 * content-addressed (name and version), so identical URLs produce identical bytes and the
 * duplicate download only costs extra bandwidth. A {@link java.nio.channels.FileLock} based
 * queue was deliberately not added because its semantics are unreliable on network file
 * systems (NFS may reject locks or silently fail to provide mutual exclusion).</p>
 */
public class CacheWriter {

    private static final int BUFFER_SIZE = 8 * 1024;

    /**
     * Opens an input stream on demand.
     *
     * <p>Abstracting the connection setup lets production code use real {@link java.net.URL}
     * downloads while tests substitute in-memory streams or {@code file:} protocol URLs
     * without touching the network.</p>
     */
    @FunctionalInterface
    public interface InputStreamOpener {
        @NonNull
        InputStream open() throws IOException;
    }

    /**
     * Downloads data to {@code destination} through a same-directory temporary file and an
     * atomic final move.
     *
     * @param destination              final cache file; an existing file is only replaced
     *                                 after the new content has been fully downloaded and
     *                                 verified
     * @param streamOpener             supplies the input stream to read the content from
     * @param expectedLengthSupplier   supplies the expected byte count, or a negative value
     *                                 when the length is unknown (for example when the
     *                                 response has no Content-Length header). In that case no
     *                                 length verification is possible, so none is performed;
     *                                 the value must not be guessed. The supplier is invoked
     *                                 only after the whole stream has been consumed
     * @throws IOException if the stream cannot be opened or read, if the number of bytes
     *                     written does not match the expected length, or if the final move
     *                     fails. The original exception is propagated and the temporary file
     *                     is removed; {@code destination} remains unchanged
     */
    public void downloadToCache(@NonNull File destination,
                                @NonNull InputStreamOpener streamOpener,
                                @NonNull LongSupplier expectedLengthSupplier) throws IOException {
        Path target = destination.toPath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // The temp file lives next to the destination so the final move stays within one
        // file system, which is what makes an atomic move possible.
        Path tempFile = Files.createTempFile(parent, destination.getName() + ".", ".tmp");
        try {
            long bytesWritten;
            try (InputStream in = new BufferedInputStream(streamOpener.open())) {
                bytesWritten = copy(in, tempFile);
            }

            long expectedLength = expectedLengthSupplier.getAsLong();
            if (expectedLength >= 0 && bytesWritten != expectedLength) {
                throw new IOException(String.format(
                        "Downloaded content length mismatch for %s: expected %d bytes but got %d",
                        destination, expectedLength, bytesWritten));
            }

            moveAtomically(tempFile, target);
        } catch (IOException | RuntimeException e) {
            // Best-effort cleanup; do not mask the original failure if deletion itself fails.
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    private static long copy(InputStream in, Path target) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private static void moveAtomically(Path tempFile, Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // The file system cannot move atomically. Fall back to a regular replace:
            // the destination still never sees partial content (that stayed in the temp
            // file), only the replacement itself may interleave with another process.
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
