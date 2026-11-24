package io.jenkins.jenkinsfile.runner.bootstrap.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CacheWriterTest {

    private static final byte[] CONTENT = "0123456789-jenkinsfile-runner-cache-content".getBytes(StandardCharsets.US_ASCII);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File newDestination(String name) {
        return new File(temporaryFolder.getRoot(), name);
    }

    private static byte[] readBytes(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    /**
     * Asserts that the directory contains no leftover temporary files.
     */
    private void assertNoTempFiles() throws IOException {
        try (Stream<Path> entries = Files.list(temporaryFolder.getRoot().toPath())) {
            boolean hasTempFile = entries.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"));
            assertFalse("Temporary file must be cleaned up after a failed download", hasTempFile);
        }
    }

    /**
     * Input stream which fails after exposing a configurable prefix of the content,
     * simulating a network drop or killed process mid-download.
     */
    private static final class FailingHalfwayInputStream extends InputStream {

        private final byte[] data;
        private final int failAfter;
        private int position;

        FailingHalfwayInputStream(byte[] data, int failAfter) {
            this.data = data;
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            if (position >= failAfter) {
                throw new IOException("Simulated read failure halfway through the download");
            }
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (position >= failAfter) {
                throw new IOException("Simulated read failure halfway through the download");
            }
            int allowed = Math.min(len, failAfter - position);
            System.arraycopy(data, position, b, off, allowed);
            position += allowed;
            return allowed;
        }
    }

    /**
     * Blocks the first read on {@code release} (after signalling {@code opened}), then serves
     * the full delegate content. All synchronization is latch-based: no sleeps, no wall clock.
     */
    private static final class BlockingFirstReadInputStream extends InputStream {

        private final InputStream delegate;
        private final CountDownLatch opened;
        private final CountDownLatch release;
        private boolean firstRead = true;

        BlockingFirstReadInputStream(InputStream delegate, CountDownLatch opened, CountDownLatch release) {
            this.delegate = delegate;
            this.opened = opened;
            this.release = release;
        }

        @Override
        public int read() throws IOException {
            gate();
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            gate();
            return delegate.read(b, off, len);
        }

        private void gate() throws IOException {
            if (firstRead) {
                firstRead = false;
                opened.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while blocked in test stream", e);
                }
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    @Test
    public void successfulDownloadWritesFullContentAtomically() throws IOException {
        File destination = newDestination("jenkins-war-2.426.3.war");
        CacheWriter writer = new CacheWriter();

        writer.downloadToCache(destination, () -> new ByteArrayInputStream(CONTENT), () -> (long) CONTENT.length);

        assertTrue("Destination file must exist after a successful download", destination.isFile());
        assertArrayEquals(CONTENT, readBytes(destination));
        assertNoTempFiles();
    }

    @Test
    public void failedDownloadLeavesNoDestinationAndNoTempFile() throws IOException {
        File destination = newDestination("structs-1.0.hpi");
        CacheWriter writer = new CacheWriter();

        try {
            writer.downloadToCache(destination,
                    () -> new FailingHalfwayInputStream(CONTENT, CONTENT.length / 2),
                    () -> (long) CONTENT.length);
            fail("The stream failure must be propagated to the caller");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Simulated read failure"));
        }

        assertFalse("Destination must not be created when the download fails", destination.exists());
        assertNoTempFiles();
    }

    @Test
    public void failedDownloadDoesNotModifyPreExistingFile() throws IOException {
        File destination = newDestination("latest.txt");
        byte[] oldContent = "previously-cached-version".getBytes(StandardCharsets.US_ASCII);
        Files.write(destination.toPath(), oldContent);

        CacheWriter writer = new CacheWriter();
        try {
            writer.downloadToCache(destination,
                    () -> new FailingHalfwayInputStream(CONTENT, CONTENT.length / 2),
                    () -> (long) CONTENT.length);
            fail("The stream failure must be propagated to the caller");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Simulated read failure"));
        }

        assertArrayEquals("The pre-existing cache entry must remain untouched", oldContent, readBytes(destination));
        assertNoTempFiles();
    }

    @Test
    public void contentLengthMismatchIsAFailureAndKeepsOldFile() throws IOException {
        File destination = newDestination("credentials-2.6.1.hpi");
        byte[] oldContent = "old-plugin".getBytes(StandardCharsets.US_ASCII);
        Files.write(destination.toPath(), oldContent);

        CacheWriter writer = new CacheWriter();
        // Fake length provider: claims more bytes than the stream actually delivers.
        long claimedLength = CONTENT.length + 100L;
        try {
            writer.downloadToCache(destination,
                    () -> new ByteArrayInputStream(CONTENT),
                    () -> claimedLength);
            fail("A Content-Length mismatch must fail the download");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("length mismatch"));
        }

        assertArrayEquals("The old cache entry must survive a length mismatch", oldContent, readBytes(destination));
        assertNoTempFiles();
    }

    @Test
    public void unknownContentLengthSkipsVerification() throws IOException {
        File destination = newDestination("no-content-length.hpi");
        CacheWriter writer = new CacheWriter();

        // Negative length means the response carried no Content-Length header:
        // verification is impossible, so the content is accepted as-is.
        writer.downloadToCache(destination, () -> new ByteArrayInputStream(CONTENT), () -> -1L);

        assertArrayEquals(CONTENT, readBytes(destination));
        assertNoTempFiles();
    }

    @Test
    public void inProgressDownloadIsNotVisibleAtFinalPathUntilComplete() throws Exception {
        File destination = newDestination("workflow-job-latest.hpi");
        CacheWriter writer = new CacheWriter();

        final CountDownLatch streamOpened = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        AtomicReference<Throwable> downloaderFailure = new AtomicReference<>();
        Thread downloader = new Thread(() -> {
            try {
                writer.downloadToCache(destination,
                        () -> new BlockingFirstReadInputStream(new ByteArrayInputStream(CONTENT), streamOpened, release),
                        () -> (long) CONTENT.length);
            } catch (Throwable t) {
                downloaderFailure.set(t);
            }
        }, "cache-writer-downloader");
        downloader.start();

        // Return as soon as the downloader has entered the stream, parked before any byte is read.
        streamOpened.await();

        // Observer acting as a second instance: the final path must not exist mid-download,
        // only a same-directory temporary file may be present.
        assertFalse("Destination must not be visible while the download is in progress", destination.exists());
        try (Stream<Path> entries = Files.list(temporaryFolder.getRoot().toPath())) {
            long tempFiles = entries.filter(path -> path.getFileName().toString().endsWith(".tmp")).count();
            assertEquals("The partial content must live in a same-directory temporary file", 1, tempFiles);
        }

        // Let the download finish and verify the complete content appears atomically.
        release.countDown();
        downloader.join();

        if (downloaderFailure.get() != null) {
            throw new AssertionError("Download failed unexpectedly", downloaderFailure.get());
        }
        assertTrue("Destination must exist once the download completes", destination.isFile());
        assertArrayEquals("The full content must be present after completion", CONTENT, readBytes(destination));
        assertNoTempFiles();
    }

    @Test
    public void concurrentDownloadsBothCompleteWithLastMoveWinning() throws Exception {
        File destination = newDestination("shared-plugin.hpi");
        CacheWriter writer = new CacheWriter();
        byte[] first = "first-instance-content".getBytes(StandardCharsets.US_ASCII);
        byte[] second = "second-instance-content".getBytes(StandardCharsets.US_ASCII);

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable firstDownload = () -> {
            awaitUninterruptibly(start);
            try {
                writer.downloadToCache(destination, () -> new ByteArrayInputStream(first), () -> (long) first.length);
            } catch (IOException e) {
                failure.compareAndSet(null, e);
            }
        };
        Runnable secondDownload = () -> {
            awaitUninterruptibly(start);
            try {
                writer.downloadToCache(destination, () -> new ByteArrayInputStream(second), () -> (long) second.length);
            } catch (IOException e) {
                failure.compareAndSet(null, e);
            }
        };

        Thread t1 = new Thread(firstDownload, "downloader-1");
        Thread t2 = new Thread(secondDownload, "downloader-2");
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();

        if (failure.get() != null) {
            throw new AssertionError("One of the concurrent downloads failed", failure.get());
        }
        byte[] result = readBytes(destination);
        assertTrue("One of the complete contents must win, never a partial mix",
                Arrays.equals(first, result) || Arrays.equals(second, result));
        assertNoTempFiles();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
