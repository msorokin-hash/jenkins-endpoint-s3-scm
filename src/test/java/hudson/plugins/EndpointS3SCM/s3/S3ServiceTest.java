package hudson.plugins.EndpointS3SCM.s3;

import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.support.BaseTest;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@DisplayName("S3Service")
public class S3ServiceTest extends BaseTest {

    @TempDir
    Path tempDir;

    private static S3Client successGetObjectClient(AtomicInteger callCounter) {
        return s3Proxy((proxy, method, args) -> {
            if ("getObject".equals(method.getName())) {
                callCounter.incrementAndGet();
                return GetObjectResponse.builder().build();
            }
            return null;
        });
    }

    private static S3Client alwaysFailGetObjectClient(RuntimeException error,
                                                      AtomicInteger callCounter) {
        return s3Proxy((proxy, method, args) -> {
            if ("getObject".equals(method.getName())) {
                callCounter.incrementAndGet();
                throw error;
            }
            return null;
        });
    }

    private static S3Client failOnceThenSucceedClient(RuntimeException error,
                                                      AtomicInteger callCounter) {
        AtomicInteger attempts = new AtomicInteger();
        return s3Proxy((proxy, method, args) -> {
            if ("getObject".equals(method.getName())) {
                callCounter.incrementAndGet();
                if (attempts.incrementAndGet() == 1) throw error;
                return GetObjectResponse.builder().build();
            }
            return null;
        });
    }

    private static S3Client s3Proxy(InvocationHandler handler) {
        return (S3Client) Proxy.newProxyInstance(
                S3ServiceTest.class.getClassLoader(),
                new Class[]{S3Client.class},
                handler);
    }

    private TaskListener newListener() {
        return new StreamTaskListener(System.out, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("getObject: success on first attempt — S3 is called exactly once")
    public void getObject_successFirstTry_downloadsOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        S3Client s3 = successGetObjectClient(calls);
        Path target = Files.createFile(tempDir.resolve("obj.bin"));

        new S3Service().getObject(s3, "bucket", "key", target, 3, 50, newListener());

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("getObject: first attempt throws — retries and succeeds on second attempt")
    public void getObject_retryOnException_succeedsOnSecondAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        S3Client s3 = failOnceThenSucceedClient(new RuntimeException("temporary error"), calls);
        Path target = Files.createFile(tempDir.resolve("retry.bin"));

        new S3Service().getObject(s3, "bucket", "key", target, 3, 10, newListener());

        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("getObject: all retries exhausted — throws IOException wrapping the original cause")
    public void getObject_failsAfterMaxRetries_throwsIOException() throws Exception {
        RuntimeException cause = new RuntimeException("permanent error");
        AtomicInteger calls = new AtomicInteger();
        S3Client s3 = alwaysFailGetObjectClient(cause, calls);
        Path target = Files.createFile(tempDir.resolve("fail.bin"));

        try {
            new S3Service().getObject(s3, "bucket", "key", target, 2, 5, newListener());
            fail("Expected IOException after all retries");
        } catch (IOException e) {
            assertEquals("Download failed", e.getMessage());
            assertSame(cause, e.getCause());
        }

        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("findLatestZipObject: returns the most recently modified ZIP, ignoring non-ZIP files")
    public void findLatestZipObject_returnsNewestZipOnly() {
        Instant oldTime = Instant.parse("2025-01-01T10:00:00Z");
        Instant newTime = Instant.parse("2025-01-02T10:00:00Z");

        TestableS3Service svc = new TestableS3Service();
        svc.setObjects(Arrays.asList(
                S3Object.builder().key("releases/app-old.zip").size(100L).lastModified(oldTime).build(),
                S3Object.builder().key("releases/app-new.zip").size(200L).lastModified(newTime).build(),
                S3Object.builder().key("releases/readme.txt").size(300L).lastModified(newTime).build()
        ));

        S3ObjectCandidate latest = svc.findLatestZipObject(null, "bucket", "releases/");

        assertNotNull(latest);
        assertEquals("releases/app-new.zip", latest.key());
        assertEquals(newTime, latest.lastModified());
    }

    @Test
    @DisplayName("findLatestZipObject: returns null when only empty-size and non-ZIP objects exist")
    public void findLatestZipObject_ignoresEmptyAndNonZipObjects() {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");

        TestableS3Service svc = new TestableS3Service();
        svc.setObjects(Arrays.asList(
                S3Object.builder().key("releases/readme.txt").size(100L).lastModified(now).build(),
                S3Object.builder().key("releases/empty.zip").size(0L).lastModified(now).build()
        ));

        S3ObjectCandidate latest = svc.findLatestZipObject(null, "bucket", "releases/");

        assertNull(latest);
    }

    @Test
    @DisplayName("findLatestZipObject: selects the object with the latest lastModified timestamp")
    public void findLatestZipObject_selectsLatestAcrossMultipleObjects() {
        Instant t1 = Instant.parse("2025-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2025-01-02T10:00:00Z");

        TestableS3Service svc = new TestableS3Service();
        svc.setObjects(Arrays.asList(
                S3Object.builder().key("1.zip").size(10L).lastModified(t1).build(),
                S3Object.builder().key("2.zip").size(20L).lastModified(t2).build()
        ));

        S3ObjectCandidate latest = svc.findLatestZipObject(null, "bucket", "releases/");

        assertNotNull(latest);
        assertEquals("2.zip", latest.key());
    }

    @Test
    @DisplayName("findLatestZipObject: strips leading slash from prefix before forwarding to S3")
    public void findLatestZipObject_normalizesLeadingSlashInPrefix() {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");

        TestableS3Service svc = new TestableS3Service();
        svc.setObjects(List.of(
                S3Object.builder().key("a.zip").size(10L).lastModified(now).build()
        ));

        svc.findLatestZipObject(null, "bucket", "/releases/");

        assertEquals("bucket", svc.capturedBucket);
        assertEquals("releases/", svc.capturedPrefix);
    }

    private static class TestableS3Service extends S3Service {
        String capturedBucket;
        String capturedPrefix;
        private List<S3Object> objects = new ArrayList<>();

        void setObjects(List<S3Object> objs) {
            this.objects = new ArrayList<>(objs);
        }

        @Override
        protected SdkIterable<S3Object> listObjects(S3Client s3, String bucket,
                                                    String normalizedPrefix) {
            capturedBucket = bucket;
            capturedPrefix = normalizedPrefix;
            return objects::iterator;
        }
    }
}
