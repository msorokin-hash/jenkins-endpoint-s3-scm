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
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.MAX_ARCHIVE_SIZE_BYTES;
import static org.junit.Assert.*;

@DisplayName("EndpointS3SCM — failure scenarios")
public class EndpointS3SCMFailureTest {

    @TempDir
    Path tempDir;

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    private UsernamePasswordCredentialsImpl creds() {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM, "creds-id", "desc", "ACCESS_KEY", "SECRET_KEY");
    }

    private EndpointS3SCM newScm() {
        S3Location loc = new S3Location();
        loc.setName("primary");
        loc.setEndpoint("http://example.com");
        loc.setBucket("bucket");
        loc.setCredentialsId("creds-id");
        loc.setPriority(10);

        EndpointS3SCM scm = new EndpointS3SCM("releases/");
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
    @DisplayName("checkout: archive exceeds 1 GB — AbortException thrown, no download attempted")
    public void checkout_abortsWhenObjectTooLarge() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-large"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        s3.putLatest("bucket", "releases/", new S3ObjectCandidate(
                "releases/huge.zip",
                MAX_ARCHIVE_SIZE_BYTES + 1,
                Instant.parse("2025-01-01T10:00:00Z")));

        EndpointS3SCM scm = newScm();
        wireServices(scm, s3, new FakeZipService());

        try {
            scm.checkout(null, null, workspace, newListener(), null, null);
            fail("Expected AbortException due to large object size");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("All S3 sources failed"));
            assertTrue(e.getMessage().contains("Archive too large"));
        }

        assertEquals(1, s3.findLatestCallCount);
        assertEquals(0, s3.getObjectCallCount);
    }

    @Test
    @DisplayName("checkout: SdkException during download — AbortException thrown, workspace cleaned up")
    public void checkout_s3SdkExceptionDuringDownload_abortsAndCleansWorkspace() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-sdkfail"));
        Path preexisting = wsDir.resolve("old.txt");
        Files.writeString(preexisting, "old content");
        assertTrue(preexisting.toFile().exists());

        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        s3.putLatest("bucket", "releases/",
                FakeS3Service.candidate("releases/app.zip", 1024L));
        s3.setGetObjectSdkException(SdkException.builder().message("boom").build());

        EndpointS3SCM scm = newScm();
        wireServices(scm, s3, new FakeZipService());

        try {
            scm.checkout(null, null, workspace, newListener(), null, null);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("All S3 sources failed"));
            assertTrue(e.getMessage().contains("S3 operation failed"));
            assertTrue(e.getMessage().contains("primary"));
        }

        assertFalse("Workspace must be cleaned up after failed checkout", preexisting.toFile().exists());
    }

    @Test
    @DisplayName("checkout: no ZIP in prefix — AbortException with 'No ZIP files found'")
    public void checkout_noZipObjectFound_abortsWithMessage() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-nozip"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();

        EndpointS3SCM scm = newScm();
        wireServices(scm, s3, new FakeZipService());

        try {
            scm.checkout(null, null, workspace, newListener(), null, null);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("All S3 sources failed"));
            assertTrue(e.getMessage().contains("No ZIP files found"));
        }
    }

    @Test
    @DisplayName("checkout: null credentials — AbortException with 'All S3 sources failed'")
    public void checkout_credentialsNotFound_abortsWithMessage() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-nocreds"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        s3.putLatest("bucket", "releases/", FakeS3Service.candidate("releases/app.zip", 1024L));

        EndpointS3SCM scm = newScm();
        scm.setCredentialLookup((run, id) -> null);
        scm.setS3Service(s3);
        scm.setZipService(new FakeZipService());
        scm.setChecksumVerifier(new NoOpChecksumVerifier());

        try {
            scm.checkout(null, null, workspace, newListener(), null, null);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("All S3 sources failed"));
        }
    }

    private static class NoOpChecksumVerifier extends ChecksumVerifier {
        @Override
        public void verify(software.amazon.awssdk.services.s3.S3Client s3,
                           hudson.plugins.EndpointS3SCM.s3.S3Service s3Service, String bucket, String zipKey,
                           Path zipFile, PrintStream log) {
        }
    }
}
