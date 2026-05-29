package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.checkout.ChecksumVerifier;
import hudson.plugins.EndpointS3SCM.config.S3Location;
import hudson.plugins.EndpointS3SCM.support.FakeS3Service;
import hudson.plugins.EndpointS3SCM.support.FakeZipService;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

@DisplayName("EndpointS3SCM — multi-source failover")
public class EndpointS3SCMFailoverTest {

    @TempDir
    Path tempDir;

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    private UsernamePasswordCredentialsImpl creds(String id) {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM, id, "desc", "ACCESS_" + id, "SECRET_" + id);
    }

    @Test
    @DisplayName("checkout: primary source failure triggers fallback to secondary in priority order")
    public void checkout_primaryFails_secondarySucceeds() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("workspace"));
        FilePath workspace = new FilePath(wsDir.toFile());

        S3Location primary = new S3Location();
        primary.setName("primary");
        primary.setEndpoint("http://primary.example.com");
        primary.setBucket("bucket-primary");
        primary.setCredentialsId("primary-creds");
        primary.setPriority(10);

        S3Location secondary = new S3Location();
        secondary.setName("secondary");
        secondary.setEndpoint("http://secondary.example.com");
        secondary.setBucket("bucket-secondary");
        secondary.setCredentialsId("secondary-creds");
        secondary.setPriority(20);

        EndpointS3SCM scm = new EndpointS3SCM("releases/");
        scm.setLocations(List.of(secondary, primary));
        scm.setCredentialLookup((run, id) -> creds(id));

        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();

        s3.throwOnFindLatest("bucket-primary", new RuntimeException("primary unavailable"));
        s3.putLatest("bucket-secondary", "releases/",
                FakeS3Service.candidate("releases/latest.zip", 1024L));

        scm.setS3Service(s3);
        scm.setZipService(zip);
        scm.setChecksumVerifier(new NoOpChecksumVerifier());

        scm.checkout(null, null, workspace, newListener(), null, null);

        assertEquals(2, s3.findLatestCallCount);
        assertEquals("bucket-primary:releases/", s3.findLatestCalls.get(0));
        assertEquals("bucket-secondary:releases/", s3.findLatestCalls.get(1));

        assertEquals(1, s3.getObjectCallCount);
        assertTrue(s3.getObjectCalls.contains("bucket-secondary:releases/latest.zip"));
        assertEquals(1, zip.extractCount);
    }

    @Test
    @DisplayName("checkout: all sources fail — AbortException thrown and workspace cleaned up")
    public void checkout_allSourcesFail_abortsAndCleansWorkspace() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-allfail"));
        Files.writeString(wsDir.resolve("stale.txt"), "old");
        FilePath workspace = new FilePath(wsDir.toFile());

        S3Location primary = new S3Location();
        primary.setName("primary");
        primary.setEndpoint("http://primary.example.com");
        primary.setBucket("bucket-primary");
        primary.setCredentialsId("creds");
        primary.setPriority(10);

        S3Location secondary = new S3Location();
        secondary.setName("secondary");
        secondary.setEndpoint("http://secondary.example.com");
        secondary.setBucket("bucket-secondary");
        secondary.setCredentialsId("creds");
        secondary.setPriority(20);

        EndpointS3SCM scm = new EndpointS3SCM("releases/");
        scm.setLocations(List.of(primary, secondary));
        scm.setCredentialLookup((run, id) -> creds(id));

        FakeS3Service s3 = new FakeS3Service();
        s3.throwOnFindLatest("bucket-primary", new RuntimeException("primary down"));
        s3.throwOnFindLatest("bucket-secondary", new RuntimeException("secondary down"));

        scm.setS3Service(s3);
        scm.setZipService(new FakeZipService());
        scm.setChecksumVerifier(new NoOpChecksumVerifier());

        try {
            scm.checkout(null, null, workspace, newListener(), null, null);
            fail("Expected AbortException");
        } catch (hudson.AbortException e) {
            assertTrue(e.getMessage().contains("All S3 sources failed"));
        }

        assertFalse("Workspace must be cleaned after total failure",
                wsDir.resolve("stale.txt").toFile().exists());
    }

    private static class NoOpChecksumVerifier extends ChecksumVerifier {
        @Override
        public void verify(software.amazon.awssdk.services.s3.S3Client s3,
                           hudson.plugins.EndpointS3SCM.s3.S3Service s3Service, String bucket, String zipKey,
                           java.nio.file.Path zipFile, java.io.PrintStream log) {
        }
    }
}
