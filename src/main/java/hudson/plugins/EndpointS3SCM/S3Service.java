package hudson.plugins.EndpointS3SCM;

import hudson.model.TaskListener;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;

import static java.net.URI.create;

/**
 * Service responsible for S3-compatible storage operations.
 * Optimized for S3-compatible systems like MinIO or Ceph using AWS SDK v2 features.
 */
public class S3Service {

    /**
     * Creates and configures an S3 client.
     *
     * @param endpoint  S3-compatible endpoint URL
     * @param region    AWS/S3 region, optional
     * @param accessKey Access key
     * @param secretKey Secret key
     * @return Configured S3Client
     */
    public S3Client createClient(String endpoint,
                                 String region,
                                 String accessKey,
                                 String secretKey) {
        S3ClientBuilder builder = S3Client.builder();
        builder.endpointOverride(create(endpoint));
        builder.region(PluginUtils.resolveRegion(region));
        builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        return builder
                .build();
    }

    /**
     * Finds the latest ZIP object inside the specified S3 prefix using SDK Paginators and Streams.
     *
     * @param s3     Configured S3 client
     * @param bucket S3 bucket name
     * @param prefix Directory/prefix to search in
     * @return Latest ZIP object candidate or null if nothing was found
     */
    public S3ObjectCandidate findLatestZipObject(S3Client s3, String bucket, String prefix) {
        return s3.listObjectsV2Paginator(b -> {
                    b.bucket(bucket).prefix(PluginUtils.normalizePrefix(prefix));
                })
                .contents()
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
     * Downloads an S3 object to a local file with retry logic using native SDK Path transfer.
     *
     * @param s3           Configured S3 client
     * @param bucket       S3 bucket name
     * @param key          S3 object key
     * @param tempFile     Local temporary file path
     * @param maxRetries   Max retry attempts
     * @param retryDelayMs Base retry delay in milliseconds
     * @param listener     Jenkins task listener
     * @throws IOException          If file operations or final download attempt fails
     * @throws InterruptedException If retry sleep is interrupted
     */
    public void getObject(S3Client s3,
                          String bucket,
                          String key,
                          Path tempFile,
                          int maxRetries,
                          int retryDelayMs,
                          TaskListener listener)
            throws IOException, InterruptedException {

        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    listener.getLogger().printf("[EndpointS3SCM] Download attempt %d of %d...%n", attempt, maxRetries);
                }

                // Native SDK v2 method to download directly to a file (more efficient than manual stream copying)
                s3.getObject(request, tempFile);
                return;

            } catch (Exception e) {
                if (attempt == maxRetries) {
                    listener.error("[EndpointS3SCM] Download failed after %d attempts: %s", maxRetries, e.getMessage());
                    if (e instanceof IOException) throw (IOException) e;
                    if (e instanceof InterruptedException) throw (InterruptedException) e;
                    throw new IOException("Download failed", e);
                }
                Thread.sleep((long) retryDelayMs * attempt);
            }
        }
    }
}