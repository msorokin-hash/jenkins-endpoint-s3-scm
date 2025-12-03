package hudson.plugins.EndpointS3SCM;

import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class S3ServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    @Test
    public void getObject_successFirstTry_writesFileOnce() throws Exception {
        byte[] data = "hello from s3".getBytes(StandardCharsets.UTF_8);

        S3Client s3 = mock(S3Client.class);

        GetObjectResponse meta = GetObjectResponse.builder()
                .contentLength((long) data.length)
                .build();

        ResponseInputStream<GetObjectResponse> ris =
                new ResponseInputStream<>(meta, new ByteArrayInputStream(data));

        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(ris);

        Path target = tmp.newFile("obj.bin").toPath();

        S3Service svc = new S3Service();
        svc.getObject(s3, "bucket", "key", target, 3, 50, newListener());

        byte[] read = Files.readAllBytes(target);
        assertArrayEquals("File content must match S3 data", data, read);

        verify(s3, times(1)).getObject(any(GetObjectRequest.class));
    }

    @Test
    public void getObject_retryOnException_thenSuccess() throws Exception {
        byte[] data = "retry success".getBytes(StandardCharsets.UTF_8);

        S3Client s3 = mock(S3Client.class);

        GetObjectResponse meta = GetObjectResponse.builder()
                .contentLength((long) data.length)
                .build();

        ResponseInputStream<GetObjectResponse> ris =
                new ResponseInputStream<>(meta, new ByteArrayInputStream(data));

        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(new RuntimeException("temporary error"))
                .thenReturn(ris);

        Path target = tmp.newFile("retry.bin").toPath();

        S3Service svc = new S3Service();
        svc.getObject(s3, "bucket", "key", target, 3, 10, newListener());

        byte[] read = Files.readAllBytes(target);
        assertArrayEquals("File content must match S3 data after retry", data, read);

        verify(s3, times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test
    public void getObject_failsAfterMaxRetries_rethrowsException() throws Exception {
        S3Client s3 = mock(S3Client.class);

        RuntimeException error = new RuntimeException("permanent error");

        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(error);

        Path target = tmp.newFile("fail.bin").toPath();

        S3Service svc = new S3Service();

        try {
            svc.getObject(s3, "bucket", "key", target, 2, 5, newListener());
            fail("Expected RuntimeException after all retries");
        } catch (RuntimeException e) {
            assertEquals("permanent error", e.getMessage());
        }

        verify(s3, times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test
    public void headObject_usesCreateClientAndReturnsResponse() {
        class TestS3Service extends S3Service {
            S3Client client;

            @Override
            public S3Client createClient(String endpoint,
                                         String region,
                                         String accessKey,
                                         String secretKey) {
                assertEquals("http://example.com", endpoint);
                assertEquals("us-east-1", region);
                assertEquals("AKIA", accessKey);
                assertEquals("SECRET", secretKey);
                return client;
            }
        }

        TestS3Service svc = new TestS3Service();
        S3Client mockClient = mock(S3Client.class);
        svc.client = mockClient;

        HeadObjectResponse resp = HeadObjectResponse.builder()
                .contentLength(42L)
                .build();

        when(mockClient.headObject(Mockito.<HeadObjectRequest>any()))
                .thenReturn(resp);

        HeadObjectResponse actual = svc.headObject(
                "http://example.com",
                "us-east-1",
                "my-bucket",
                "obj.zip",
                "AKIA",
                "SECRET"
        );

        assertEquals(42L, (long) actual.contentLength());

        ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(mockClient, times(1)).headObject(captor.capture());
        HeadObjectRequest sent = captor.getValue();
        assertEquals("my-bucket", sent.bucket());
        assertEquals("obj.zip", sent.key());
    }
}