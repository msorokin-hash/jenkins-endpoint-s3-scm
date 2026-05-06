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

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class EndpointS3SCMFailoverTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    private UsernamePasswordCredentialsImpl creds(String id) {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM,
                id,
                "desc",
                "ACCESS_" + id,
                "SECRET_" + id
        );
    }

    @Test
    public void checkout_primaryFails_secondarySucceeds() throws Exception {
        File wsDir = temp.newFolder("workspace");
        FilePath workspace = new FilePath(wsDir);

        Run<?, ?> run = mock(Run.class);
        Launcher launcher = mock(Launcher.class);
        TaskListener listener = newListener();

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

        try (MockedStatic<CredentialsHelper> credsStatic =
                     Mockito.mockStatic(CredentialsHelper.class)) {

            credsStatic.when(() -> CredentialsHelper.lookupCredentialsForRun(run, "primary-creds"))
                    .thenReturn(creds("primary-creds"));

            credsStatic.when(() -> CredentialsHelper.lookupCredentialsForRun(run, "secondary-creds"))
                    .thenReturn(creds("secondary-creds"));

            S3Service s3Service = mock(S3Service.class);
            ZipService zipService = mock(ZipService.class);

            S3Client primaryClient = mock(S3Client.class);
            S3Client secondaryClient = mock(S3Client.class);

            when(s3Service.createClient(
                    "http://primary.example.com",
                    null,
                    "ACCESS_primary-creds",
                    "SECRET_primary-creds"
            )).thenReturn(primaryClient);

            when(s3Service.createClient(
                    "http://secondary.example.com",
                    null,
                    "ACCESS_secondary-creds",
                    "SECRET_secondary-creds"
            )).thenReturn(secondaryClient);

            when(s3Service.findLatestZipObject(primaryClient, "bucket-primary", "releases/"))
                    .thenThrow(new RuntimeException("primary unavailable"));

            S3ObjectCandidate latest = new S3ObjectCandidate(
                    "releases/latest.zip",
                    1024L,
                    Instant.parse("2025-01-02T10:00:00Z")
            );

            when(s3Service.findLatestZipObject(secondaryClient, "bucket-secondary", "releases/"))
                    .thenReturn(latest);

            doAnswer(invocation -> {
                Path tempFile = invocation.getArgument(3);
                java.nio.file.Files.writeString(tempFile, "dummy");
                return null;
            }).when(s3Service).getObject(
                    eq(secondaryClient),
                    eq("bucket-secondary"),
                    eq("releases/latest.zip"),
                    any(Path.class),
                    anyInt(),
                    anyInt(),
                    any(TaskListener.class)
            );

            doNothing().when(zipService)
                    .validateZipFile(any(Path.class), any(TaskListener.class));

            doNothing().when(zipService)
                    .extractZipSecurely(
                            any(Path.class),
                            any(FilePath.class),
                            any(TaskListener.class),
                            anyBoolean()
                    );

            injectField(scm, "s3Service", s3Service);
            injectField(scm, "zipService", zipService);

            scm.checkout(run, launcher, workspace, listener, null, null);

            verify(s3Service).findLatestZipObject(primaryClient, "bucket-primary", "releases/");
            verify(s3Service).findLatestZipObject(secondaryClient, "bucket-secondary", "releases/");

            verify(s3Service).getObject(
                    eq(secondaryClient),
                    eq("bucket-secondary"),
                    eq("releases/latest.zip"),
                    any(Path.class),
                    anyInt(),
                    anyInt(),
                    any(TaskListener.class)
            );

            verify(zipService).extractZipSecurely(
                    any(Path.class),
                    eq(workspace),
                    any(TaskListener.class),
                    eq(true)
            );
        }
    }
}