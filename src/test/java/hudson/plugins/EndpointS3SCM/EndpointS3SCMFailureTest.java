package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

            when(s3ServiceMock.createClient("http://example.com", null, "ACCESS_KEY", "SECRET_KEY"))
                    .thenReturn(s3ClientMock);

            HeadObjectResponse headResp = HeadObjectResponse.builder()
                    .contentLength(LARGE_OBJECT_WARNING_SIZE + 1)
                    .build();
            when(s3ClientMock.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(headResp);

            EndpointS3SCM scm = new EndpointS3SCM("http://example.com", "bucket", "key.zip");
            scm.setCredentialsId("creds-id");

            injectField(scm, "s3Service", s3ServiceMock);
            injectField(scm, "zipService", zipServiceMock);

            try {
                scm.checkout(run, launcher, workspace, listener, null, null);
                fail("Expected AbortException due to large object size");
            } catch (hudson.AbortException e) {
                assertTrue("Message must mention 'Archive is too large'",
                        e.getMessage().contains("Archive is too large"));
            }

            verify(s3ServiceMock, never()).getObject(
                    any(S3Client.class),
                    anyString(),
                    anyString(),
                    any(Path.class),
                    anyInt(),
                    anyInt(),
                    any(TaskListener.class)
            );
            verify(zipServiceMock, never()).validateZipFile(any(Path.class), any(TaskListener.class));
            verify(zipServiceMock, never()).extractZipSecurely(any(Path.class), any(FilePath.class), any(TaskListener.class), anyBoolean());
        }
    }

    @Test
    public void checkout_s3ServiceThrowsSdkException_resultsInIOExceptionAndWorkspaceCleanup() throws Exception {
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

            when(s3ServiceMock.createClient("http://example.com", null, "ACCESS_KEY", "SECRET_KEY"))
                    .thenReturn(s3ClientMock);

            HeadObjectResponse headResp = HeadObjectResponse.builder()
                    .contentLength(1024L)
                    .build();
            when(s3ClientMock.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(headResp);

            doAnswer(invocation -> {
                throw SdkException.builder()
                        .message("boom")
                        .build();
            }).when(s3ServiceMock).getObject(
                    eq(s3ClientMock),
                    eq("bucket"),
                    eq("key.zip"),
                    any(Path.class),
                    anyInt(),
                    anyInt(),
                    any(TaskListener.class)
            );

            EndpointS3SCM scm = new EndpointS3SCM("http://example.com", "bucket", "key.zip");
            scm.setCredentialsId("creds-id");

            injectField(scm, "s3Service", s3ServiceMock);
            injectField(scm, "zipService", zipServiceMock);

            IOException thrown;
            try {
                scm.checkout(run, launcher, workspace, listener, null, null);
                fail("Expected IOException due to S3 client error");
                return;
            } catch (IOException e) {
                thrown = e;
            }


            assertTrue(thrown.getMessage().contains("Failed to checkout from S3-compatible endpoint"));

            Throwable cause1 = thrown.getCause();
            assertNotNull(cause1);
            assertTrue(cause1 instanceof IOException);
            assertTrue(cause1.getMessage().contains("S3 operation failed"));

            Throwable cause2 = cause1.getCause();
            assertNotNull(cause2);
            assertTrue(cause2 instanceof SdkException);
            assertEquals("boom", cause2.getMessage());

            verify(s3ClientMock, times(1)).headObject(any(HeadObjectRequest.class));
            verify(s3ServiceMock, times(1))
                    .getObject(eq(s3ClientMock), eq("bucket"), eq("key.zip"),
                            any(Path.class), anyInt(), anyInt(), any(TaskListener.class));

            verify(zipServiceMock, never()).validateZipFile(any(Path.class), any(TaskListener.class));
            verify(zipServiceMock, never()).extractZipSecurely(any(Path.class), any(FilePath.class), any(TaskListener.class), anyBoolean());

            assertFalse("Workspace must be cleaned up after failed checkout", preexisting.exists());
            assertTrue(wsDir.exists());
        }
    }
}