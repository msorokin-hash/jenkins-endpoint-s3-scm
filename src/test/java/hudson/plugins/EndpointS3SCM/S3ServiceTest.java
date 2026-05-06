package hudson.plugins.EndpointS3SCM;

import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request.Builder;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class S3ServiceTest extends BaseTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    private ListObjectsV2Iterable iterable(ListObjectsV2Response... responses) {
        ListObjectsV2Iterable mockIterable = mock(ListObjectsV2Iterable.class);

        List<S3Object> allObjects = Arrays.stream(responses)
                .flatMap(r -> r.contents().stream())
                .collect(Collectors.toList());

        doReturn(allObjects.iterator()).when(mockIterable).iterator();
        when(mockIterable.contents()).thenReturn(allObjects::iterator);

        return mockIterable;
    }

    @Test
    public void getObject_successFirstTry_downloadsToFileOnce() throws Exception {
        S3Client s3 = mock(S3Client.class);
        Path target = tmp.newFile("obj.bin").toPath();

        doReturn(GetObjectResponse.builder().contentLength(13L).build())
                .when(s3).getObject(any(GetObjectRequest.class), eq(target));

        S3Service svc = new S3Service();
        svc.getObject(s3, "bucket", "key", target, 3, 50, newListener());

        verify(s3, times(1)).getObject(any(GetObjectRequest.class), eq(target));
    }

    @Test
    public void getObject_retryOnException_thenSuccess() throws Exception {
        S3Client s3 = mock(S3Client.class);
        Path target = tmp.newFile("retry.bin").toPath();

        when(s3.getObject(any(GetObjectRequest.class), eq(target)))
                .thenThrow(new RuntimeException("temporary error"))
                .thenReturn(GetObjectResponse.builder().contentLength(13L).build());

        S3Service svc = new S3Service();
        svc.getObject(s3, "bucket", "key", target, 3, 10, newListener());

        verify(s3, times(2)).getObject(any(GetObjectRequest.class), eq(target));
    }

    @Test
    public void getObject_failsAfterMaxRetries_rethrowsIOException() throws Exception {
        S3Client s3 = mock(S3Client.class);
        Path target = tmp.newFile("fail.bin").toPath();

        RuntimeException error = new RuntimeException("permanent error");

        doThrow(error).when(s3).getObject(any(GetObjectRequest.class), eq(target));

        S3Service svc = new S3Service();

        try {
            svc.getObject(s3, "bucket", "key", target, 2, 5, newListener());
            fail("Expected IOException after all retries");
        } catch (Exception e) {
            assertTrue(e instanceof IOException);
            assertEquals("Download failed", e.getMessage());
            assertEquals(error, e.getCause());
        }

        verify(s3, times(2)).getObject(any(GetObjectRequest.class), eq(target));
    }

    @Test
    public void findLatestZipObject_returnsNewestZipOnly() {
        S3Client s3 = mock(S3Client.class);

        Instant oldTime = Instant.parse("2025-01-01T10:00:00Z");
        Instant newTime = Instant.parse("2025-01-02T10:00:00Z");

        ListObjectsV2Response response = ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder().key("releases/app-old.zip").size(100L).lastModified(oldTime).build(),
                        S3Object.builder().key("releases/app-new.zip").size(200L).lastModified(newTime).build(),
                        S3Object.builder().key("releases/readme.txt").size(300L).lastModified(newTime).build()
                )
                .build();

        ListObjectsV2Iterable mockResult = iterable(response);
        doReturn(mockResult).when(s3).listObjectsV2Paginator(any(Consumer.class));

        S3Service svc = new S3Service();
        S3ObjectCandidate latest = svc.findLatestZipObject(s3, "bucket", "releases/");

        assertNotNull(latest);
        assertEquals("releases/app-new.zip", latest.getKey());
        assertEquals(newTime, latest.getLastModified());
    }

    @Test
    public void findLatestZipObject_ignoresEmptyAndNonZipObjects() {
        S3Client s3 = mock(S3Client.class);
        Instant now = Instant.parse("2025-01-01T10:00:00Z");

        ListObjectsV2Response response = ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder().key("releases/readme.txt").size(100L).lastModified(now).build(),
                        S3Object.builder().key("releases/empty.zip").size(0L).lastModified(now).build()
                )
                .build();

        doReturn(iterable(response)).when(s3).listObjectsV2Paginator(any(Consumer.class));

        S3ObjectCandidate latest = new S3Service().findLatestZipObject(s3, "bucket", "releases/");

        assertNull(latest);
    }

    @Test
    public void findLatestZipObject_supportsPagination() {
        S3Client s3 = mock(S3Client.class);
        Instant pageOneTime = Instant.parse("2025-01-01T10:00:00Z");
        Instant pageTwoTime = Instant.parse("2025-01-02T10:00:00Z");

        ListObjectsV2Response pageOne = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("1.zip").size(10L).lastModified(pageOneTime).build())
                .build();
        ListObjectsV2Response pageTwo = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("2.zip").size(20L).lastModified(pageTwoTime).build())
                .build();

        doReturn(iterable(pageOne, pageTwo)).when(s3).listObjectsV2Paginator(any(Consumer.class));

        S3ObjectCandidate latest = new S3Service().findLatestZipObject(s3, "bucket", "releases/");

        assertNotNull(latest);
        assertEquals("2.zip", latest.getKey());
    }

    @Test
    public void findLatestZipObject_normalizesLeadingSlashInPrefix() {
        S3Client s3 = mock(S3Client.class);
        ListObjectsV2Response response = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("a.zip").size(10L).lastModified(Instant.now()).build())
                .build();

        doReturn(iterable(response)).when(s3).listObjectsV2Paginator(any(Consumer.class));

        new S3Service().findLatestZipObject(s3, "bucket", "/releases/");

        ArgumentCaptor<Consumer<Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3).listObjectsV2Paginator(captor.capture());

        Builder builder = ListObjectsV2Request.builder();
        captor.getValue().accept(builder);
        ListObjectsV2Request request = builder.build();

        assertEquals("bucket", request.bucket());
        assertEquals("releases/", request.prefix());
    }
}