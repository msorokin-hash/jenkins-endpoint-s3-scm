package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static hudson.plugins.EndpointS3SCM.PluginUtils.isEmpty;
import static hudson.plugins.EndpointS3SCM.PluginUtils.maskUrl;
import static java.net.URI.create;

/**
 * Jenkins SCM implementation for downloading and extracting ZIP archives from S3-compatible storage.
 * This plugin enables Jenkins jobs to source their source code from ZIP files stored in S3,
 * supporting custom endpoints like MinIO, Ceph, and other S3-compatible services.
 *
 * @author Mikhail Sorokin
 */
public class EndpointS3SCM extends SCM {

    /**
     * Maximum size threshold for S3 objects (1GB).
     * Objects larger than this will trigger an abort during checkout.
     */
    public static final long LARGE_OBJECT_WARNING_SIZE = 1_000_000_000L;

    private final String endpoint;
    private final String bucket;
    private final String key;
    private final S3Service s3Service = new S3Service();
    private final ZipService zipService = new ZipService();
    private String credentialsId;
    private String region;
    private int maxRetries = 3;
    private int retryDelayMs = 1000;
    private boolean stripTopLevelDir = true;

    /**
     * Constructor for the SCM plugin with required parameters.
     *
     * @param endpoint S3-compatible endpoint URL
     * @param bucket   S3 bucket name containing the ZIP archive
     * @param key      S3 object key (path) to the ZIP file
     */
    @DataBoundConstructor
    public EndpointS3SCM(String endpoint, String bucket, String key) {
        this.endpoint = Util.fixEmptyAndTrim(endpoint);
        this.bucket = Util.fixEmptyAndTrim(bucket);
        this.key = Util.fixEmptyAndTrim(key);
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getBucket() {
        return bucket;
    }

    @NonNull
    public String getKey() {
        return key;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public String getRegion() {
        return region;
    }

    @DataBoundSetter
    public void setRegion(String region) {
        this.region = Util.fixEmptyAndTrim(region);
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Sets the maximum number of retry attempts for failed downloads.
     * Clamped between 1 and 10 attempts.
     */
    @DataBoundSetter
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(1, Math.min(maxRetries, 10));
    }

    public int getRetryDelayMs() {
        return retryDelayMs;
    }

    /**
     * Sets the base retry delay in milliseconds between download attempts.
     * Clamped between 500ms and 30,000ms (30 seconds).
     */
    @DataBoundSetter
    public void setRetryDelayMs(int retryDelayMs) {
        this.retryDelayMs = Math.max(500, Math.min(retryDelayMs, 30000));
    }

    public boolean isStripTopLevelDir() {
        return stripTopLevelDir;
    }

    @DataBoundSetter
    public void setStripTopLevelDir(boolean stripTopLevelDir) {
        this.stripTopLevelDir = stripTopLevelDir;
    }

    /**
     * Main checkout method called by Jenkins during build execution.
     * Downloads a ZIP file from S3, validates it, and extracts it securely to the workspace.
     *
     * @param build         Current Jenkins build context
     * @param launcher      Jenkins launcher for executing processes
     * @param workspace     Jenkins workspace directory where files will be extracted
     * @param listener      Build listener for logging
     * @param changelogFile File to write changelog information (unused for this SCM)
     * @param baseline      Baseline revision state (unused for this SCM)
     * @throws IOException If checkout fails due to I/O errors, network issues, or security violations
     */
    @Override
    public void checkout(@NonNull Run<?, ?> build,
                         @NonNull Launcher launcher,
                         @NonNull FilePath workspace,
                         @NonNull TaskListener listener,
                         File changelogFile,
                         SCMRevisionState baseline) throws IOException {

        validateConfig();

        var log = listener.getLogger();
        log.println("[EndpointS3SCM] Checkout from S3-compatible endpoint");
        log.printf("[EndpointS3SCM] endpoint: %s, bucket: %s%n", maskUrl(endpoint), bucket);
        log.printf("[EndpointS3SCM] key: %s%n", key);
        if (!isEmpty(region)) {
            log.printf("[EndpointS3SCM] region: %s%n", region);
        }
        log.printf("[EndpointS3SCM] maxRetries: %d, retryDelayMs: %dms%n", maxRetries, retryDelayMs);
        log.printf("[EndpointS3SCM] stripTopLevelDir: %s%n", stripTopLevelDir);

        // Retrieve credentials from Jenkins credentials store
        StandardUsernamePasswordCredentials creds =
                CredentialsHelper.lookupCredentialsForRun(build, credentialsId);
        if (creds == null) {
            throw new AbortException("[EndpointS3SCM] Cannot find credentials with id: " + credentialsId);
        }

        validateCredentials(creds);

        Path tempFile = null;
        boolean success = false;

        try {
            // Create temporary file for downloading the ZIP archive
            tempFile = Files.createTempFile("endpoint-s3scm-", ".zip");

            String accessKey = creds.getUsername();
            String secretKey = creds.getPassword().getPlainText();

            // Create S3 client and perform operations
            try (S3Client s3 = s3Service.createClient(endpoint, region, accessKey, secretKey)) {

                log.println("[EndpointS3SCM] Checking object size before download...");

                // Get object metadata to check size before downloading
                HeadObjectRequest headReq = HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();

                HeadObjectResponse response;
                try {
                    response = s3.headObject(headReq);
                } catch (NoSuchKeyException e) {
                    throw new AbortException("[EndpointS3SCM] Object not found: " + key);
                } catch (S3Exception e) {
                    String msg = e.awsErrorDetails() != null
                            ? e.awsErrorDetails().errorMessage()
                            : e.getMessage();
                    throw new AbortException("[EndpointS3SCM] Failed to read object metadata: " + msg);
                }

                long objectSize = response.contentLength();
                log.printf("[EndpointS3SCM] Object size: %d bytes%n", objectSize);

                // Check size limit before downloading
                if (objectSize > LARGE_OBJECT_WARNING_SIZE) {
                    throw new AbortException(String.format(
                            "[EndpointS3SCM] Archive is too large (%d bytes). Maximum allowed size: %d bytes.",
                            objectSize, LARGE_OBJECT_WARNING_SIZE
                    ));
                }

                // Download the ZIP file from S3
                log.println("[EndpointS3SCM] Downloading object from storage...");
                s3Service.getObject(s3,
                        bucket,
                        key,
                        tempFile,
                        maxRetries,
                        retryDelayMs,
                        listener);

                long size = Files.size(tempFile);
                log.printf("[EndpointS3SCM] Download complete, size: %d bytes%n", size);

                // Validate ZIP file structure
                zipService.validateZipFile(tempFile, listener);

                // Prepare workspace
                workspace.mkdirs();
                workspace.deleteContents();

                // Extract ZIP archive with security protections
                log.println("[EndpointS3SCM] Extracting ZIP archive into workspace (safe mode)...");
                zipService.extractZipSecurely(tempFile, workspace, listener, stripTopLevelDir);

                log.println("[EndpointS3SCM] Checkout finished successfully.");
                success = true;


            } catch (SdkException e) {
                listener.error("[EndpointS3SCM] S3 client error: " + e.getMessage());
                throw new IOException("S3 operation failed", e);
            }
        } catch (AbortException e) {
            listener.error("[EndpointS3SCM] Checkout aborted: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            listener.error("[EndpointS3SCM] Checkout failed: " + e.getMessage());
            if (e.getCause() != null) {
                listener.error("[EndpointS3SCM] Cause: " + e.getCause().getMessage());
            }
            throw new IOException("Failed to checkout from S3-compatible endpoint", e);
        } finally {
            // Clean up temporary file
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.printf("[EndpointS3SCM] Warning: cannot delete temp file %s: %s%n",
                            tempFile, e.getMessage());
                }
            }

            // Clean up workspace if checkout failed
            if (!success) {
                try {
                    workspace.deleteContents();
                    log.println("[EndpointS3SCM] Workspace cleaned up due to failed checkout.");
                } catch (Exception e) {
                    log.printf("[EndpointS3SCM] Warning: failed to clean workspace: %s%n", e.getMessage());
                }
            }
        }
    }

    /**
     * Indicates whether this SCM supports polling for changes.
     * This implementation does not support polling.
     *
     * @return false - polling is not supported
     */
    @Override
    public boolean supportsPolling() {
        return false;
    }

    /**
     * Adds S3 configuration as environment variables to the build.
     * These variables can be used in subsequent build steps.
     *
     * @param build Current Jenkins build
     * @param env   Environment variables map to populate
     */
    @Override
    public void buildEnvironment(@NonNull Run<?, ?> build,
                                 @NonNull Map<String, String> env) {
        env.put("ENDPOINT_S3_ENDPOINT", endpoint != null ? endpoint : "");
        env.put("ENDPOINT_S3_BUCKET", bucket != null ? bucket : "");
        env.put("ENDPOINT_S3_KEY", key != null ? key : "");
        if (credentialsId != null) {
            env.put("ENDPOINT_S3_CREDENTIALS_ID", credentialsId);
        }
        if (!isEmpty(region)) {
            env.put("ENDPOINT_S3_REGION", region);
        }
    }

    /**
     * Creates a changelog parser for this SCM.
     * Since this SCM doesn't track changes, returns a NullChangeLogParser.
     *
     * @return NullChangeLogParser instance
     */
    @Override
    public ChangeLogParser createChangeLogParser() {
        return new NullChangeLogParser();
    }

    /**
     * Calculates revision state from the current build.
     * Not implemented as this SCM doesn't track revisions.
     *
     * @return null - revision state not applicable
     */
    @Override
    public SCMRevisionState calcRevisionsFromBuild(
            @NonNull Run<?, ?> build,
            @Nullable FilePath workspace,
            @Nullable Launcher launcher,
            @NonNull TaskListener listener) {

        return null;
    }

    @Override
    public SCMDescriptor<?> getDescriptor() {
        return super.getDescriptor();
    }

    /**
     * Validates the configuration parameters before proceeding with checkout.
     *
     * @throws AbortException If any required parameter is missing or invalid
     */
    private void validateConfig() throws AbortException {
        if (isEmpty(endpoint)) {
            throw new AbortException("[EndpointS3SCM] Endpoint is required");
        }

        try {
            create(endpoint);
        } catch (Exception e) {
            throw new AbortException("[EndpointS3SCM] Invalid endpoint URL: " + endpoint);
        }

        if (isEmpty(bucket)) {
            throw new AbortException("[EndpointS3SCM] Bucket is required");
        }
        if (isEmpty(key)) {
            throw new AbortException("[EndpointS3SCM] Key is required");
        }
        if (isEmpty(credentialsId)) {
            throw new AbortException("[EndpointS3SCM] Credentials are required");
        }
    }

    /**
     * Validates that retrieved credentials contain both access key and secret.
     *
     * @param creds Credentials retrieved from Jenkins credentials store
     * @throws AbortException If credentials are missing username or password
     */
    private void validateCredentials(StandardUsernamePasswordCredentials creds) throws AbortException {
        if (isEmpty(creds.getUsername())) {
            throw new AbortException("[EndpointS3SCM] Selected credentials have empty username (access key)");
        }
        if (isEmpty(creds.getPassword().getPlainText())) {
            throw new AbortException("[EndpointS3SCM] Selected credentials have empty password (secret)");
        }
    }
}