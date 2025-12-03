package hudson.plugins.EndpointS3SCM;

import hudson.model.TaskListener;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static hudson.plugins.EndpointS3SCM.PluginUtils.isEmpty;
import static java.net.URI.create;

public class S3Service {

    /**
     * Creates and configures an S3 client for connecting to an S3-compatible storage endpoint.
     * Supports custom endpoints (like MinIO, Ceph, etc.) with optional region configuration.
     *
     * @param endpoint  The S3 service endpoint URL (e.g., "https://s3.amazonaws.com" or "http://localhost:9000")
     * @param region    AWS region (optional, can be empty for non-AWS S3 implementations)
     * @param accessKey AWS access key ID for authentication
     * @param secretKey AWS secret access key for authentication
     * @return Configured S3Client instance ready for use
     */
    public S3Client createClient(String endpoint,
                                 String region,
                                 String accessKey,
                                 String secretKey) {
        AwsBasicCredentials creds = AwsBasicCredentials.create(accessKey, secretKey);

        S3Configuration serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();

        S3ClientBuilder builder = S3Client.builder()
                .endpointOverride(create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .serviceConfiguration(serviceConfiguration);

        if (!isEmpty(region)) {
            try {
                builder.region(Region.of(region));
            } catch (Exception ignored) {
                // Silently ignore invalid region - some S3 implementations don't require it
            }
        }

        return builder.build();
    }

    /**
     * Downloads an object from S3 storage to a local temporary file with retry logic.
     * Implements exponential backoff retries for transient failures.
     *
     * @param s3           Configured S3 client instance
     * @param bucket       S3 bucket name containing the object
     * @param key          S3 object key (path) to download
     * @param tempFile     Local file path where the object will be saved
     * @param maxRetries   Maximum number of retry attempts for failed downloads
     * @param retryDelayMs Base delay in milliseconds between retries (increases with each attempt)
     * @param listener     Jenkins task listener for logging progress and errors
     * @throws IOException          If an I/O error occurs during file operations
     * @throws InterruptedException If the thread is interrupted during sleep between retries
     * @throws S3Exception          If all retry attempts fail due to S3 errors
     */
    public void getObject(S3Client s3,
                          String bucket,
                          String key,
                          Path tempFile,
                          int maxRetries,
                          int retryDelayMs,
                          TaskListener listener)
            throws IOException, InterruptedException {

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    listener.getLogger().printf(
                            "[EndpointS3SCM] Download attempt %d of %d...%n",
                            attempt, maxRetries
                    );
                }

                try (ResponseInputStream<GetObjectResponse> in = s3.getObject(request);
                     OutputStream out = Files.newOutputStream(tempFile)) {

                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }

                return; // Success - exit method

            } catch (S3Exception e) {
                if (attempt == maxRetries) {
                    listener.error("[EndpointS3SCM] S3 error: " + e.getMessage());
                    e.printStackTrace(listener.getLogger());
                    throw e; // Re-throw after max retries
                }
                Thread.sleep((long) retryDelayMs * attempt); // Linear backoff

            } catch (Exception e) {
                if (attempt == maxRetries) {
                    listener.error("[EndpointS3SCM] Download failed: " + e.getMessage());
                    e.printStackTrace(listener.getLogger());
                    throw e; // Re-throw after max retries
                }
                Thread.sleep((long) retryDelayMs * attempt); // Linear backoff
            }
        }
    }

    /**
     * Retrieves metadata about an S3 object without downloading its contents.
     * Useful for checking object existence, size, last modified date, etc.
     * Creates a temporary S3 client for a single operation.
     *
     * @param endpoint  S3 service endpoint URL
     * @param region    AWS region (optional)
     * @param bucket    S3 bucket name
     * @param key       S3 object key (path)
     * @param accessKey AWS access key ID
     * @param secretKey AWS secret access key
     * @return HeadObjectResponse containing object metadata, or throws S3Exception if object doesn't exist
     */
    public HeadObjectResponse headObject(String endpoint,
                                         String region,
                                         String bucket,
                                         String key,
                                         String accessKey,
                                         String secretKey) {
        try (S3Client s3 = createClient(endpoint, region, accessKey, secretKey)) {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            return s3.headObject(request);
        }
    }
}