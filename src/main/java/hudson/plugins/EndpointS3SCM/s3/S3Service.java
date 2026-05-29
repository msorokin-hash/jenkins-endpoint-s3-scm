package hudson.plugins.EndpointS3SCM.s3;

import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.util.PluginUtils;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.LOG_PREFIX;
import static java.net.URI.create;
import static software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create;

/**
 * Service responsible for S3-compatible storage operations.
 * Optimized for S3-compatible systems like MinIO or Ceph using AWS SDK v2 features.
 */
public class S3Service {

    /**
     * Creates and configures an S3 client.
     */
    public S3Client createClient(String endpoint, String region, String accessKey, String secretKey) {
        S3ClientBuilder builder = S3Client.builder();
        builder.endpointOverride(create(endpoint));
        builder.region(PluginUtils.resolveRegion(region));
        builder.credentialsProvider(StaticCredentialsProvider.create(
                create(accessKey, secretKey)));
        builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        return builder.build();
    }

    /**
     * Downloads an S3 object as a UTF-8 string, or returns {@code null} if the key does not exist.
     */
    public String tryGetObjectContent(S3Client s3, String bucket, String key) {
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()
            );
            return bytes.asUtf8String();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /**
     * Retrieves metadata for a specific S3 object without downloading its content (HEAD request).
     *
     * @return object metadata, or {@code null} if the key does not exist or has zero size
     */
    public S3ObjectCandidate headObject(S3Client s3, String bucket, String key) {
        String normalizedKey = PluginUtils.normalizePrefix(key);
        try {
            var response = s3.headObject(b -> b.bucket(bucket).key(normalizedKey));
            long size = response.contentLength() != null ? response.contentLength() : 0L;
            if (size <= 0) {
                return null;
            }
            return new S3ObjectCandidate(normalizedKey, size, response.lastModified());
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /**
     * Verifies that the S3 client can reach the bucket (HEAD bucket request).
     */
    public void checkBucketAccess(S3Client s3, String bucket) {
        s3.headBucket(b -> b.bucket(bucket));
    }

    /**
     * Finds the latest ZIP object inside the specified S3 prefix.
     */
    public S3ObjectCandidate findLatestZipObject(S3Client s3, String bucket, String prefix) {
        String normalizedPrefix = PluginUtils.normalizePrefix(prefix);
        return listObjects(s3, bucket, normalizedPrefix)
                .stream()
                .filter(obj -> obj.key() != null)
                .filter(obj -> obj.lastModified() != null)
                .filter(obj -> obj.size() != null && obj.size() > 0)
                .filter(obj -> obj.key().toLowerCase().endsWith(".zip"))
                .max(Comparator.comparing(S3Object::lastModified))
                .map(obj -> new S3ObjectCandidate(obj.key(), obj.size(), obj.lastModified()))
                .orElse(null);
    }

    /**
     * Returns an iterable of S3 objects. Overridden in tests to inject controlled lists.
     */
    protected SdkIterable<S3Object> listObjects(S3Client s3, String bucket, String normalizedPrefix) {
        return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(normalizedPrefix)).contents();
    }

    /**
     * Downloads an S3 object to a local file with retry logic.
     */
    public void getObject(S3Client s3, String bucket, String key, Path tempFile,
                          int maxRetries, int retryDelayMs, TaskListener listener)
            throws IOException, InterruptedException {

        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    listener.getLogger().printf(LOG_PREFIX + "Download attempt %d of %d...%n", attempt, maxRetries);
                }
                s3.getObject(request, tempFile);
                return;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    listener.error(LOG_PREFIX + "Download failed after %d attempts: %s", maxRetries, e.getMessage());
                    if (e instanceof IOException) throw (IOException) e;
                    if (e instanceof InterruptedException) throw (InterruptedException) e;
                    throw new IOException("Download failed", e);
                }
                Thread.sleep((long) retryDelayMs * attempt);
            }
        }
    }
}
