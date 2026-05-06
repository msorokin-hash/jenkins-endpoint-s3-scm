package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCM.LARGE_OBJECT_WARNING_SIZE;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class EndpointS3SCMFailureTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    private UsernamePasswordCredentialsImpl newCreds() {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM,
                "creds-id",
                "desc",
                "ACCESS_KEY",
                "SECRET_KEY"
        );
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field f = EndpointS3SCM.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private EndpointS3SCM newScm() {
        S3Location location = new S3Location();
        location.setEndpoint("http://example.com");
        location.setRegion("us-east-1");
        location.setName("primary");
        location.setBucket("bucket");
        location.setCredentialsId("creds-id");
        location.setPriority(10);

        EndpointS3SCM scm = new EndpointS3SCM("releases/");
        scm.setLocations(Collections.singletonList(location));

        return scm;
    }

    @Test
    public void checkout_abortsWhenObjectTooLarge() throws Exception {
        File wsDir = temp.newFolder("workspace");
        FilePath workspace = new FilePath(wsDir);

        Run<?, ?> run = mock(Run.class);
        Launcher launcher = mock(Launcher.class);
        TaskListener listener = newListener();

        UsernamePasswordCredentialsImpl creds = newCreds();

        try (MockedStatic<CredentialsHelper> credsStatic =
                     Mockito.mockStatic(CredentialsHelper.class)) {

            credsStatic.when(() -> CredentialsHelper.lookupCredentialsForRun(run, "creds-id"))
                    .thenReturn(creds);

            S3Service s3ServiceMock = mock(S3Service.class);
            ZipService zipServiceMock = mock(ZipService.class);
            S3Client s3ClientMock = mock(S3Client.class);

            when(s3ServiceMock.createClient(
                    eq("http://example.com"),
                    any(),
                    eq("ACCESS_KEY"),
                    anyString()
            )).thenReturn(s3ClientMock);

            S3ObjectCandidate tooLargeObject = new S3ObjectCandidate(
                    "releases/key.zip",
                    LARGE_OBJECT_WARNING_SIZE + 1,
                    Instant.parse("2025-01-01T10:00:00Z")
            );

            when(s3ServiceMock.findLatestZipObject(
                    s3ClientMock,
                    "bucket",
                    "releases/"
            )).thenReturn(tooLargeObject);

            EndpointS3SCM scm = newScm();

            injectField(scm, "s3Service", s3ServiceMock);
            injectField(scm, "zipService", zipServiceMock);

            try {
                scm.checkout(run, launcher, workspace, listener, null, null);
                fail("Expected AbortException due to large object size");
            } catch (AbortException e) {
                // ПРОВЕРКА: Новые тексты сообщений
                assertTrue(e.getMessage().contains("All S3 sources failed"));
                assertTrue(e.getMessage().contains("Archive too large"));
            }

            verify(s3ServiceMock, times(1))
                    .findLatestZipObject(s3ClientMock, "bucket", "releases/");
        }
    }

    @Test
    public void checkout_s3ServiceThrowsSdkException_resultsInAbortExceptionAndWorkspaceCleanup() throws Exception {
        File wsDir = temp.newFolder("workspace2");
        File preexisting = new File(wsDir, "old.txt");
        Files.writeString(preexisting.toPath(), "old content");
        assertTrue(preexisting.exists());

        FilePath workspace = new FilePath(wsDir);

        Run<?, ?> run = mock(Run.class);
        Launcher launcher = mock(Launcher.class);
        TaskListener listener = newListener();

        UsernamePasswordCredentialsImpl creds = newCreds();

        try (MockedStatic<CredentialsHelper> credsStatic =
                     Mockito.mockStatic(CredentialsHelper.class)) {

            credsStatic.when(() -> CredentialsHelper.lookupCredentialsForRun(run, "creds-id"))
                    .thenReturn(creds);

            S3Service s3ServiceMock = mock(S3Service.class);
            ZipService zipServiceMock = mock(ZipService.class);
            S3Client s3ClientMock = mock(S3Client.class);

            when(s3ServiceMock.createClient(anyString(), any(), anyString(), anyString()))
                    .thenReturn(s3ClientMock);

            S3ObjectCandidate latestObject = new S3ObjectCandidate(
                    "releases/key.zip",
                    1024L,
                    Instant.parse("2025-01-01T10:00:00Z")
            );

            when(s3ServiceMock.findLatestZipObject(s3ClientMock, "bucket", "releases/"))
                    .thenReturn(latestObject);

            doThrow(SdkException.builder().message("boom").build())
                    .when(s3ServiceMock)
                    .getObject(any(), anyString(), anyString(), any(), anyInt(), anyInt(), any());

            EndpointS3SCM scm = newScm();
            injectField(scm, "s3Service", s3ServiceMock);
            injectField(scm, "zipService", zipServiceMock);

            try {
                scm.checkout(run, launcher, workspace, listener, null, null);
                fail("Expected AbortException");
            } catch (AbortException e) {
                assertTrue(e.getMessage().contains("All S3 sources failed"));
                assertTrue(e.getMessage().contains("S3 operation failed"));
                assertTrue(e.getMessage().contains("primary"));
            }

            assertFalse("Workspace must be cleaned up after failed checkout", preexisting.exists());
        }
    }
}