package hudson.plugins.EndpointS3SCM.support;

import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.s3.S3ObjectCandidate;
import hudson.plugins.EndpointS3SCM.s3.S3Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeS3Service extends S3Service {

    public final List<String> findLatestCalls = new ArrayList<>();
    public final List<String> getObjectCalls = new ArrayList<>();
    public final List<String> headObjectCalls = new ArrayList<>();

    private final Map<String, S3ObjectCandidate> latestObjects = new HashMap<>();
    private final Map<String, S3ObjectCandidate> exactObjects = new HashMap<>();
    private final Map<String, RuntimeException> findLatestExceptions = new HashMap<>();
    private final Map<String, String> objectContents = new HashMap<>();
    public int findLatestCallCount;
    public int getObjectCallCount;
    public int headObjectCallCount;
    private SdkException getObjectSdkException;
    private IOException getObjectIOException;

    public static S3ObjectCandidate candidate(String key, long size) {
        return new S3ObjectCandidate(key, size, Instant.parse("2025-01-01T10:00:00Z"));
    }

    @Override
    public S3Client createClient(String endpoint, String region, String accessKey, String secretKey) {
        return null;
    }

    @Override
    public S3ObjectCandidate findLatestZipObject(S3Client s3, String bucket, String prefix) {
        findLatestCallCount++;
        findLatestCalls.add(bucket + ":" + prefix);
        RuntimeException ex = findLatestExceptions.get(bucket);
        if (ex != null) throw ex;
        return latestObjects.get(bucket + ":" + prefix);
    }

    @Override
    public void getObject(S3Client s3, String bucket, String key, Path tempFile,
                          int maxRetries, int retryDelayMs, TaskListener listener)
            throws IOException {
        getObjectCallCount++;
        getObjectCalls.add(bucket + ":" + key);
        if (getObjectSdkException != null) throw getObjectSdkException;
        if (getObjectIOException != null) throw getObjectIOException;
        Files.writeString(tempFile, "fake-zip-content");
    }

    @Override
    public S3ObjectCandidate headObject(S3Client s3, String bucket, String key) {
        headObjectCallCount++;
        headObjectCalls.add(bucket + ":" + key);
        return exactObjects.get(bucket + ":" + key);
    }

    @Override
    public String tryGetObjectContent(S3Client s3, String bucket, String key) {
        return objectContents.get(bucket + ":" + key);
    }

    @Override
    public void checkBucketAccess(S3Client s3, String bucket) {
    }

    public void putLatest(String bucket, String prefix, S3ObjectCandidate candidate) {
        latestObjects.put(bucket + ":" + prefix, candidate);
    }

    public void putExact(String bucket, String key, S3ObjectCandidate candidate) {
        exactObjects.put(bucket + ":" + key, candidate);
    }

    public void throwOnFindLatest(String bucket, RuntimeException ex) {
        findLatestExceptions.put(bucket, ex);
    }

    public void putContent(String bucket, String key, String content) {
        objectContents.put(bucket + ":" + key, content);
    }

    public void setGetObjectSdkException(SdkException ex) {
        this.getObjectSdkException = ex;
    }

    public void setGetObjectIOException(IOException ex) {
        this.getObjectIOException = ex;
    }
}
