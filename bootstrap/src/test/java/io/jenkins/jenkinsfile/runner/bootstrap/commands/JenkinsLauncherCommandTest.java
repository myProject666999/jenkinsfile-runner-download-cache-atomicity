package io.jenkins.jenkinsfile.runner.bootstrap.commands;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class JenkinsLauncherCommandTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /**
     * A {@code file:} URL is not an {@link java.net.HttpURLConnection}; before the connection
     * handling was relaxed to {@link java.net.URLConnection} this call threw a
     * {@link ClassCastException}. It must now download through the cache writer just like HTTP.
     */
    @Test
    public void copiesFileProtocolUrlWithoutCastingToHttpUrlConnection() throws Exception {
        byte[] content = "file-protocol-download-content".getBytes(StandardCharsets.US_ASCII);
        File source = temporaryFolder.newFile("source.hpi");
        Files.write(source.toPath(), content);

        File destination = new File(temporaryFolder.newFolder("cache"), "plugin-1.0.hpi");

        // Concrete command subclass; only the URL-to-file download path is exercised,
        // so no Jenkins options or network access are needed.
        JenkinsLauncherCommand command = new RunJenkinsfileCommand();
        command.copyURLToFile(source.toURI().toURL(), destination);

        assertTrue("The file: URL content must be downloaded to the destination", destination.isFile());
        assertArrayEquals(content, Files.readAllBytes(destination.toPath()));
    }
}
