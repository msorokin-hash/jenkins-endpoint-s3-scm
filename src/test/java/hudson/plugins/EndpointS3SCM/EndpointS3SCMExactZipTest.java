package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.AbortException;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.checkout.ChecksumVerifier;
import hudson.plugins.EndpointS3SCM.config.S3Location;
import hudson.plugins.EndpointS3SCM.s3.S3ObjectCandidate;
import hudson.plugins.EndpointS3SCM.support.FakeS3Service;
import hudson.plugins.EndpointS3SCM.support.FakeZipService;
import hudson.plugins.EndpointS3SCM.util.PluginUtils;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.MAX_ARCHIVE_SIZE_BYTES;
import static org.junit.Assert.*;

@DisplayName("EndpointS3SCM — exact ZIP mode")
public class EndpointS3SCMExactZipTest {

    @TempDir
    Path tempDir;

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    private UsernamePasswordCredentialsImpl creds() {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM, "creds-id", "desc", "ACCESS_KEY", "SECRET_KEY");
    }

    private EndpointS3SCM newScm(String prefix) {
        S3Location loc = new S3Location();
        loc.setName("primary");
        loc.setEndpoint("http://example.com");
        loc.setBucket("bucket");
        loc.setCredentialsId("creds-id");
        loc.setPriority(10);

        EndpointS3SCM scm = new EndpointS3SCM(prefix);
        scm.setLocations(Collections.singletonList(loc));
        scm.setCredentialLookup((run, id) -> creds());
        return scm;
    }

    private void wireServices(EndpointS3SCM scm, FakeS3Service s3, FakeZipService zip) {
        scm.setS3Service(s3);
        scm.setZipService(zip);
        scm.setChecksumVerifier(new NoOpChecksumVerifier());
    }

    @Test
    @DisplayName("checkout: exact ZIP key — downloads specific object, skips prefix listing")
    public void checkout_exactZipKey_downloadsSpecificObjectWithoutListing() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("workspace"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();
        s3.putExact("bucket", "releases/app-2.0.zip",
                FakeS3Service.candidate("releases/app-2.0.zip", 2048L));

        EndpointS3SCM scm = newScm("releases/app-2.0.zip");
        wireServices(scm, s3, zip);

        scm.checkout(null, null, workspace, newListener(), null, null);

        // HEAD request issued, no listing
        assertEquals(1, s3.headObjectCallCount);
        assertTrue(s3.headObjectCalls.contains("bucket:releases/app-2.0.zip"));
        assertEquals(0, s3.findLatestCallCount);

        // Object downloaded and extracted
        assertEquals(1, s3.getObjectCallCount);
        assertTrue(s3.getObjectCalls.contains("bucket:releases/app-2.0.zip"));
        assertEquals(1, zip.validateCount);
        assertEquals(1, zip.extractCount);
    }

    @Test
    @DisplayName("checkout: exact ZIP key — case-insensitive .ZIP extension recognised")
    public void checkout_exactZipKey_caseInsensitiveExtension() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-uppercase"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();
        // Upper-case .ZIP
        s3.putExact("bucket", "releases/ARCHIVE.ZIP",
                FakeS3Service.candidate("releases/ARCHIVE.ZIP", 512L));

        EndpointS3SCM scm = newScm("releases/ARCHIVE.ZIP");
        wireServices(scm, s3, zip);

        scm.checkout(null, null, workspace, newListener(), null, null);

        assertEquals(1, s3.headObjectCallCount);
        assertEquals(0, s3.findLatestCallCount);
        assertEquals(1, zip.extractCount);
    }

    @Test
    @DisplayName("checkout: exact ZIP key not found — AbortException with descriptive message")
    public void checkout_exactZipKeyNotFound_abortsWithMessage() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-notfound"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();   // headObject returns null for any key

        EndpointS3SCM scm = newScm("releases/missing.zip");
        wireServices(scm, s3, new FakeZipService());

        try {
            scm.checkout(null, null, workspace, newListener(), null, null);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("All S3 sources failed"));
            assertTrue(e.getMessage().contains("No ZIP file found at key"));
            assertTrue(e.getMessage().contains("releases/missing.zip"));
        }

        assertEquals(1, s3.headObjectCallCount);
        assertEquals(0, s3.getObjectCallCount);
    }

    @Test
    @DisplayName("checkout: exact ZIP key exceeds size limit — AbortException before download")
    public void checkout_exactZipKeyTooLarge_abortsBeforeDownload() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-toolarge"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        s3.putExact("bucket", "releases/huge.zip",
                new S3ObjectCandidate("releases/huge.zip",
                        MAX_ARCHIVE_SIZE_BYTES + 1,
                        Instant.parse("2025-01-01T10:00:00Z")));

        EndpointS3SCM scm = newScm("releases/huge.zip");
        wireServices(scm, s3, new FakeZipService());

        try {
            scm.checkout(null, null, workspace, newListener(), null, null);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("All S3 sources failed"));
            assertTrue(e.getMessage().contains("Archive too large"));
        }

        // HEAD issued, but no download attempted
        assertEquals(1, s3.headObjectCallCount);
        assertEquals(0, s3.getObjectCallCount);
    }

    @Test
    @DisplayName("isExactZipKey: directory prefix without .zip — prefix mode selected")
    public void isExactZipKey_directoryPrefix_returnsFalse() {
        assertFalse(PluginUtils.isExactZipKey("releases/backend"));
        assertFalse(PluginUtils.isExactZipKey("releases/backend/"));
        assertFalse(PluginUtils.isExactZipKey("releases/"));
        assertFalse(PluginUtils.isExactZipKey(""));
        assertFalse(PluginUtils.isExactZipKey(null));
    }

    @Test
    @DisplayName("isExactZipKey: key ending with .zip/.ZIP — exact mode selected")
    public void isExactZipKey_zipKey_returnsTrue() {
        assertTrue(PluginUtils.isExactZipKey("releases/app.zip"));
        assertTrue(PluginUtils.isExactZipKey("releases/app.ZIP"));
        assertTrue(PluginUtils.isExactZipKey("releases/app.Zip"));
        assertTrue(PluginUtils.isExactZipKey("app.zip"));
    }

    @Test
    @DisplayName("checkout: non-.zip prefix still uses prefix listing mode")
    public void checkout_nonZipPrefix_usesPrefixListingMode() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-prefix"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();
        s3.putLatest("bucket", "releases/",
                FakeS3Service.candidate("releases/latest.zip", 1024L));

        EndpointS3SCM scm = newScm("releases/");
        wireServices(scm, s3, zip);

        scm.checkout(null, null, workspace, newListener(), null, null);

        assertEquals(1, s3.findLatestCallCount);
        assertEquals(0, s3.headObjectCallCount);
        assertEquals(1, s3.getObjectCallCount);
        assertEquals(1, zip.extractCount);
    }

    private static class NoOpChecksumVerifier extends ChecksumVerifier {
        @Override
        public void verify(software.amazon.awssdk.services.s3.S3Client s3,
                           hudson.plugins.EndpointS3SCM.s3.S3Service s3Service, String bucket, String zipKey,
                           Path zipFile, PrintStream log) {
        }
    }
}
