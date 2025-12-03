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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class EndpointS3SCMTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field f = EndpointS3SCM.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    @Test
    public void checkout_happyPath() throws Exception {
        File wsDir = temp.newFolder("workspace");
        FilePath workspace = new FilePath(wsDir);

        Run<?, ?> run = mock(Run.class);
        Launcher launcher = mock(Launcher.class);
        TaskListener listener = newListener();

        UsernamePasswordCredentialsImpl creds =
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.SYSTEM,
                        "creds-id",
                        "desc",
                        "ACCESS_KEY",
                        "SECRET_KEY"
                );

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
                Path tempFile = invocation.getArgument(3);
                java.nio.file.Files.writeString(tempFile, "dummy");
                return null;
            }).when(s3ServiceMock).getObject(
                    eq(s3ClientMock),
                    eq("bucket"),
                    eq("key.zip"),
                    any(Path.class),
                    anyInt(),
                    anyInt(),
                    any(TaskListener.class)
            );

            doNothing().when(zipServiceMock)
                    .validateZipFile(any(Path.class), any(TaskListener.class));
            doNothing().when(zipServiceMock)
                    .extractZipSecurely(any(Path.class), any(FilePath.class), any(TaskListener.class), anyBoolean());

            EndpointS3SCM scm = new EndpointS3SCM("http://example.com", "bucket", "key.zip");
            scm.setCredentialsId("creds-id");

            injectField(scm, "s3Service", s3ServiceMock);
            injectField(scm, "zipService", zipServiceMock);

            scm.checkout(run, launcher, workspace, listener, null, null);

            assertTrue(wsDir.exists());

            verify(s3ServiceMock, times(1))
                    .createClient("http://example.com", null, "ACCESS_KEY", "SECRET_KEY");
            verify(s3ClientMock, times(1)).headObject(any(HeadObjectRequest.class));
            verify(s3ServiceMock, times(1))
                    .getObject(eq(s3ClientMock), eq("bucket"), eq("key.zip"), any(Path.class), anyInt(), anyInt(), any(TaskListener.class));
            verify(zipServiceMock, times(1))
                    .validateZipFile(any(Path.class), any(TaskListener.class));
            verify(zipServiceMock, times(1))
                    .extractZipSecurely(any(Path.class), any(FilePath.class), any(TaskListener.class), eq(true));
        }
    }
}