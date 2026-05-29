package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.checkout.PrefixResolver;
import hudson.plugins.EndpointS3SCM.checkout.S3CheckoutAction;
import hudson.plugins.EndpointS3SCM.checkout.S3SCMRevisionState;
import hudson.plugins.EndpointS3SCM.config.CheckoutConfigResolver;
import hudson.plugins.EndpointS3SCM.config.S3Location;
import hudson.plugins.EndpointS3SCM.s3.S3ObjectCandidate;
import hudson.plugins.EndpointS3SCM.s3.S3PollingService;
import hudson.plugins.EndpointS3SCM.support.FakeS3Service;
import hudson.scm.PollingResult;
import hudson.scm.SCMRevisionState;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@DisplayName("EndpointS3SCM — polling / change detection")
public class EndpointS3SCMPollingTest {

    private static final Instant T1 = Instant.parse("2025-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2025-01-02T10:00:00Z");

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    private UsernamePasswordCredentialsImpl creds() {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM, "creds-id", "desc", "ACCESS", "SECRET");
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

    private PollingResult compare(EndpointS3SCM scm,
                                  FakeS3Service s3,
                                  SCMRevisionState baseline) throws Exception {
        S3PollingService pollingService =
                new S3PollingService(s3, new CheckoutConfigResolver(), new PrefixResolver());

        Run<?, ?> lastBuild = null;

        return pollingService.compare(
                lastBuild,
                baseline,
                newListener(),
                scm.getPrefix(),
                scm.getLocations(),
                (run, id) -> creds()
        );
    }

    @Test
    @DisplayName("supportsPolling: returns true")
    public void supportsPolling_returnsTrue() {
        assertTrue(new EndpointS3SCM("releases/").supportsPolling());
    }

    @Test
    @DisplayName("calcRevisionsFromBuild: action present — returns S3SCMRevisionState")
    public void calcRevisionsFromBuild_actionPresent_returnsState() {
        S3CheckoutAction action = new S3CheckoutAction(
                "releases/app-1.0.zip", "bucket", "primary", T1, 1024L, "LATEST");

        S3SCMRevisionState state = new S3SCMRevisionState(
                action.getBucket(), action.getKey(), action.getLastModifiedMs());

        assertEquals("bucket", state.getBucket());
        assertEquals("releases/app-1.0.zip", state.getKey());
        assertEquals(T1.toEpochMilli(), state.getLastModifiedMs());
    }

    @Test
    @DisplayName("compare: non-S3SCMRevisionState baseline → BUILD_NOW")
    public void compare_nonS3Baseline_buildNow() throws Exception {
        EndpointS3SCM scm = newScm("releases/");
        FakeS3Service s3 = new FakeS3Service();

        PollingResult result = compare(scm, s3, SCMRevisionState.NONE);

        assertEquals(PollingResult.BUILD_NOW, result);
    }

    @Test
    @DisplayName("compare: same key + same timestamp → NO_CHANGES")
    public void compare_prefixMode_noChange() throws Exception {
        FakeS3Service s3 = new FakeS3Service();
        s3.putLatest("bucket", "releases/",
                new S3ObjectCandidate("releases/app-1.0.zip", 1024L, T1));

        EndpointS3SCM scm = newScm("releases/");

        S3SCMRevisionState baseline =
                new S3SCMRevisionState("bucket", "releases/app-1.0.zip", T1.toEpochMilli());

        PollingResult result = compare(scm, s3, baseline);

        assertEquals(PollingResult.NO_CHANGES, result);
        assertEquals(1, s3.findLatestCallCount);
        assertEquals(0, s3.headObjectCallCount);
    }

    @Test
    @DisplayName("compare: new ZIP key found → SIGNIFICANT")
    public void compare_prefixMode_newKeyFound_significant() throws Exception {
        FakeS3Service s3 = new FakeS3Service();
        s3.putLatest("bucket", "releases/",
                new S3ObjectCandidate("releases/app-2.0.zip", 2048L, T2));

        EndpointS3SCM scm = newScm("releases/");

        S3SCMRevisionState baseline =
                new S3SCMRevisionState("bucket", "releases/app-1.0.zip", T1.toEpochMilli());

        PollingResult result = compare(scm, s3, baseline);

        assertEquals(PollingResult.SIGNIFICANT, result);
    }

    @Test
    @DisplayName("compare: same key but newer timestamp → SIGNIFICANT")
    public void compare_prefixMode_sameKeyNewerTimestamp_significant() throws Exception {
        FakeS3Service s3 = new FakeS3Service();
        s3.putLatest("bucket", "releases/",
                new S3ObjectCandidate("releases/app-1.0.zip", 1024L, T2));

        EndpointS3SCM scm = newScm("releases/");

        S3SCMRevisionState baseline =
                new S3SCMRevisionState("bucket", "releases/app-1.0.zip", T1.toEpochMilli());

        PollingResult result = compare(scm, s3, baseline);

        assertEquals(PollingResult.SIGNIFICANT, result);
    }

    @Test
    @DisplayName("compare: no object found at source → NO_CHANGES (conservative)")
    public void compare_noObjectFound_noChanges() throws Exception {
        FakeS3Service s3 = new FakeS3Service();

        EndpointS3SCM scm = newScm("releases/");

        S3SCMRevisionState baseline =
                new S3SCMRevisionState("bucket", "releases/app-1.0.zip", T1.toEpochMilli());

        PollingResult result = compare(scm, s3, baseline);

        assertEquals(PollingResult.NO_CHANGES, result);
    }

    @Test
    @DisplayName("compare: exact ZIP mode, same object → NO_CHANGES")
    public void compare_exactMode_noChange() throws Exception {
        FakeS3Service s3 = new FakeS3Service();
        s3.putExact("bucket", "releases/app-1.0.zip",
                new S3ObjectCandidate("releases/app-1.0.zip", 1024L, T1));

        EndpointS3SCM scm = newScm("releases/app-1.0.zip");

        S3SCMRevisionState baseline =
                new S3SCMRevisionState("bucket", "releases/app-1.0.zip", T1.toEpochMilli());

        PollingResult result = compare(scm, s3, baseline);

        assertEquals(PollingResult.NO_CHANGES, result);
        assertEquals(0, s3.findLatestCallCount);
        assertEquals(1, s3.headObjectCallCount);
    }

    @Test
    @DisplayName("compare: exact ZIP mode, object updated → SIGNIFICANT")
    public void compare_exactMode_objectUpdated_significant() throws Exception {
        FakeS3Service s3 = new FakeS3Service();
        s3.putExact("bucket", "releases/app-1.0.zip",
                new S3ObjectCandidate("releases/app-1.0.zip", 1024L, T2));

        EndpointS3SCM scm = newScm("releases/app-1.0.zip");

        S3SCMRevisionState baseline =
                new S3SCMRevisionState("bucket", "releases/app-1.0.zip", T1.toEpochMilli());

        PollingResult result = compare(scm, s3, baseline);

        assertEquals(PollingResult.SIGNIFICANT, result);
    }

    @Test
    @DisplayName("compare: primary unavailable, secondary has changes → SIGNIFICANT")
    public void compare_primaryUnavailable_secondaryChanged() throws Exception {
        S3Location primary = new S3Location();
        primary.setName("primary");
        primary.setEndpoint("http://primary.example.com");
        primary.setBucket("bucket-primary");
        primary.setCredentialsId("creds-id");
        primary.setPriority(10);

        S3Location secondary = new S3Location();
        secondary.setName("secondary");
        secondary.setEndpoint("http://secondary.example.com");
        secondary.setBucket("bucket-secondary");
        secondary.setCredentialsId("creds-id");
        secondary.setPriority(20);

        FakeS3Service s3 = new FakeS3Service();
        s3.throwOnFindLatest("bucket-primary", new RuntimeException("primary down"));
        s3.putLatest("bucket-secondary", "releases/",
                new S3ObjectCandidate("releases/app-2.0.zip", 2048L, T2));

        EndpointS3SCM scm = new EndpointS3SCM("releases/");
        scm.setLocations(List.of(primary, secondary));
        scm.setCredentialLookup((run, id) -> creds());

        S3SCMRevisionState baseline =
                new S3SCMRevisionState("bucket-secondary", "releases/app-1.0.zip", T1.toEpochMilli());

        PollingResult result = compare(scm, s3, baseline);

        assertEquals(PollingResult.SIGNIFICANT, result);
    }
}