package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.checkout.ChecksumVerifier;
import hudson.plugins.EndpointS3SCM.checkout.S3CheckoutAction;
import hudson.plugins.EndpointS3SCM.config.S3Location;
import hudson.plugins.EndpointS3SCM.s3.S3ObjectCandidate;
import hudson.plugins.EndpointS3SCM.support.FakeBuild;
import hudson.plugins.EndpointS3SCM.support.FakeS3Service;
import hudson.plugins.EndpointS3SCM.support.FakeZipService;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.MAX_ARCHIVE_SIZE_BYTES;
import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("EndpointS3SCM — happy path and core behaviour")
public class EndpointS3SCMTest {

    @TempDir
    Path tempDir;

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    private UsernamePasswordCredentialsImpl creds() {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM, "creds-id", "desc", "ACCESS_KEY", "SECRET_KEY");
    }

    private EndpointS3SCM scm(String name, String endpoint, String bucket,
                              String credentialsId, int priority) {
        S3Location loc = new S3Location();
        loc.setName(name);
        loc.setEndpoint(endpoint);
        loc.setBucket(bucket);
        loc.setCredentialsId(credentialsId);
        loc.setPriority(priority);

        EndpointS3SCM scm = new EndpointS3SCM("releases/");
        scm.setLocations(List.of(loc));
        scm.setCredentialLookup((run, id) -> creds());
        return scm;
    }

    private void wireServices(EndpointS3SCM scm, FakeS3Service s3, FakeZipService zip) {
        scm.setS3Service(s3);
        scm.setZipService(zip);
        scm.setChecksumVerifier(new NoOpChecksumVerifier());
    }

    @Test
    @DisplayName("checkout: happy path — finds latest ZIP, downloads, validates, and extracts into workspace")
    public void checkout_happyPath_downloadsExtractsAndCleansUp() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("workspace"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();

        s3.putLatest("bucket", "releases/", new S3ObjectCandidate(
                "releases/app-1.0.zip", 1024L, Instant.parse("2025-01-01T10:00:00Z")));

        EndpointS3SCM scm = scm("primary", "http://example.com", "bucket", "creds-id", 10);
        wireServices(scm, s3, zip);

        scm.checkout(null, null, workspace, newListener(), null, null);

        assertTrue(wsDir.toFile().exists());
        assertTrue(s3.findLatestCalls.contains("bucket:releases/"));
        assertTrue(s3.getObjectCalls.contains("bucket:releases/app-1.0.zip"));
        assertEquals(1, zip.validateCount);
        assertEquals(1, zip.extractCount);
        assertTrue(zip.lastStripTopLevelDir);
    }

    @Test
    @DisplayName("checkout: no ZIP found in prefix — throws AbortException")
    public void checkout_noZipFound_abortsWithMessage() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-nozip"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        EndpointS3SCM scm = scm("primary", "http://example.com", "bucket", "creds-id", 10);
        wireServices(scm, s3, new FakeZipService());

        assertThrows(hudson.AbortException.class, () ->
                scm.checkout(null, null, workspace, newListener(), null, null));
    }

    @Test
    @DisplayName("checkout: archive exceeds size limit — throws AbortException before download")
    public void checkout_archiveTooLarge_aborts() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-large"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        s3.putLatest("bucket", "releases/", new S3ObjectCandidate(
                "releases/huge.zip", MAX_ARCHIVE_SIZE_BYTES + 1,
                Instant.parse("2025-01-01T10:00:00Z")));

        EndpointS3SCM scm = scm("primary", "http://example.com", "bucket", "creds-id", 10);
        wireServices(scm, s3, new FakeZipService());

        assertThrows(hudson.AbortException.class, () ->
                scm.checkout(null, null, workspace, newListener(), null, null));
    }

    @Test
    @DisplayName("checkout: credential lookup returns null — throws AbortException")
    public void checkout_credentialsNull_aborts() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-nocreds"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        s3.putLatest("bucket", "releases/", FakeS3Service.candidate("releases/app.zip", 1024L));

        EndpointS3SCM scm = scm("primary", "http://example.com", "bucket", "creds-id", 10);
        scm.setCredentialLookup((run, id) -> null);
        scm.setS3Service(s3);
        scm.setZipService(new FakeZipService());
        scm.setChecksumVerifier(new NoOpChecksumVerifier());

        assertThrows(hudson.AbortException.class, () ->
                scm.checkout(null, null, workspace, newListener(), null, null));
    }

    @Test
    @DisplayName("checkout: targetDirectory set — ZIP extracted into subdirectory, not workspace root")
    public void checkout_withTargetDirectory_extractsIntoSubdir() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-target"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();
        s3.putLatest("bucket", "releases/", FakeS3Service.candidate("releases/app.zip", 512L));

        EndpointS3SCM scm = scm("primary", "http://example.com", "bucket", "creds-id", 10);
        scm.setTargetDirectory("backend");
        wireServices(scm, s3, zip);

        scm.checkout(null, null, workspace, newListener(), null, null);

        assertEquals(1, zip.extractCount);
        assertTrue("Extraction must target workspace/backend subdirectory",
                zip.lastExtractWorkspace.getRemote().endsWith("backend"));
    }

    @Test
    @DisplayName("checkout: no targetDirectory — ZIP extracted into workspace root")
    public void checkout_withoutTargetDirectory_extractsIntoWorkspaceRoot() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-root"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();
        s3.putLatest("bucket", "releases/", FakeS3Service.candidate("releases/app.zip", 512L));

        EndpointS3SCM scm = scm("primary", "http://example.com", "bucket", "creds-id", 10);
        wireServices(scm, s3, zip);

        scm.checkout(null, null, workspace, newListener(), null, null);

        assertEquals(wsDir.toFile().getAbsolutePath(), zip.lastExtractWorkspace.getRemote());
    }

    @Test
    @DisplayName("checkout: S3CheckoutAction attached to build on success")
    public void checkout_happyPath_actionContainsCorrectMetadata() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-action"));
        FilePath workspace = new FilePath(wsDir.toFile());

        Instant modified = Instant.parse("2025-03-15T08:00:00Z");
        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();
        s3.putLatest("bucket", "releases/", new S3ObjectCandidate(
                "releases/app-3.0.zip", 8192L, modified));

        EndpointS3SCM scm = scm("primary", "http://example.com", "bucket", "creds-id", 10);
        wireServices(scm, s3, zip);

        // Use a FakeBuild that collects added actions
        FakeBuild build = new FakeBuild();
        scm.checkout(build, null, workspace, newListener(), null, null);

        S3CheckoutAction action = build.getAction(S3CheckoutAction.class);
        assertNotNull("S3CheckoutAction must be attached to build after successful checkout", action);
        assertEquals("releases/app-3.0.zip", action.getKey());
        assertEquals("bucket", action.getBucket());
        assertEquals("primary", action.getSourceName());
        assertEquals(modified, action.getLastModified());
        assertEquals(8192L, action.getSize());
        assertEquals("LATEST", action.getMode());
    }

    @Test
    @DisplayName("checkout: exact ZIP mode recorded in action")
    public void checkout_exactZipMode_actionHasExactMode() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-exact-action"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();
        s3.putExact("bucket", "releases/app-1.0.zip",
                FakeS3Service.candidate("releases/app-1.0.zip", 1024L));

        S3Location loc = new S3Location();
        loc.setName("primary");
        loc.setEndpoint("http://example.com");
        loc.setBucket("bucket");
        loc.setCredentialsId("creds-id");
        loc.setPriority(10);

        EndpointS3SCM scm = new EndpointS3SCM("releases/app-1.0.zip");
        scm.setLocations(List.of(loc));
        scm.setCredentialLookup((run, id) -> creds());
        wireServices(scm, s3, zip);

        FakeBuild build = new FakeBuild();
        scm.checkout(build, null, workspace, newListener(), null, null);

        S3CheckoutAction action = build.getAction(S3CheckoutAction.class);
        assertNotNull(action);
        assertEquals("EXACT", action.getMode());
    }

    @Test
    @DisplayName("checkout: @script workspace — no S3CheckoutAction attached to build (silent mode)")
    public void checkout_scriptWorkspace_noActionAttached() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("workspace@script"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();
        s3.putLatest("bucket", "releases/", FakeS3Service.candidate("releases/app.zip", 512L));

        EndpointS3SCM scm = scm("primary", "http://example.com", "bucket", "creds-id", 10);
        wireServices(scm, s3, zip);

        FakeBuild build = new FakeBuild();
        scm.checkout(build, null, workspace, newListener(), null, null);

        // Checkout itself succeeds (ZIP downloaded and extracted)
        assertEquals(1, zip.extractCount);
        // But no action is added — @script checkouts are silent
        assertNull("S3CheckoutAction must NOT be attached for @script checkout",
                build.getAction(S3CheckoutAction.class));
    }

    @Test
    @DisplayName("checkout: @libs workspace — no S3CheckoutAction attached to build (silent mode)")
    public void checkout_libsWorkspace_noActionAttached() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("workspace@libs"));
        FilePath workspace = new FilePath(wsDir.toFile());

        FakeS3Service s3 = new FakeS3Service();
        FakeZipService zip = new FakeZipService();
        s3.putLatest("bucket", "releases/", FakeS3Service.candidate("releases/app.zip", 512L));

        EndpointS3SCM scm = scm("primary", "http://example.com", "bucket", "creds-id", 10);
        wireServices(scm, s3, zip);

        FakeBuild build = new FakeBuild();
        scm.checkout(build, null, workspace, newListener(), null, null);

        assertEquals(1, zip.extractCount);
        assertNull("S3CheckoutAction must NOT be attached for @libs checkout",
                build.getAction(S3CheckoutAction.class));
    }

    @Test
    @DisplayName("checkout: empty prefix — throws AbortException with validation error")
    public void checkout_emptyPrefix_aborts() throws Exception {
        Path wsDir = Files.createDirectory(tempDir.resolve("ws-nopfx"));
        FilePath workspace = new FilePath(wsDir.toFile());

        EndpointS3SCM scm = new EndpointS3SCM("");
        S3Location loc = new S3Location();
        loc.setName("primary");
        loc.setEndpoint("http://example.com");
        loc.setBucket("bucket");
        loc.setCredentialsId("creds-id");
        loc.setPriority(10);
        scm.setLocations(List.of(loc));
        scm.setCredentialLookup((run, id) -> creds());

        assertThrows(hudson.AbortException.class, () ->
                scm.checkout(null, null, workspace, newListener(), null, null));
    }

    private static class NoOpChecksumVerifier extends ChecksumVerifier {
        @Override
        public void verify(software.amazon.awssdk.services.s3.S3Client s3,
                           hudson.plugins.EndpointS3SCM.s3.S3Service s3Service, String bucket, String zipKey,
                           Path zipFile, PrintStream log) {
        }
    }
}
