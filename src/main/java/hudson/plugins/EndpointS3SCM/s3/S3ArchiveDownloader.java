package hudson.plugins.EndpointS3SCM.s3;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.checkout.ChecksumVerifier;
import hudson.plugins.EndpointS3SCM.config.CheckoutConfig;
import hudson.plugins.EndpointS3SCM.config.EndpointS3SCMConfigValidator;
import hudson.plugins.EndpointS3SCM.config.S3Location;
import hudson.plugins.EndpointS3SCM.util.PluginUtils;
import hudson.util.Secret;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiFunction;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.LOG_PREFIX;
import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.MAX_ARCHIVE_SIZE_BYTES;

/**
 * Downloads the latest or a specific ZIP archive from a configured S3 location.
 *
 * <p>This service handles credentials resolution, S3 client creation, ZIP discovery,
 * size validation, download retry delegation, and optional checksum verification.</p>
 *
 * <h2>Operating modes</h2>
 * <p>The operating mode is determined by the caller from the already-expanded prefix:</p>
 * <ul>
 *   <li><b>Prefix mode</b> — prefix does not end with {@code .zip}: lists objects and
 *       downloads the latest by {@code lastModified}.</li>
 *   <li><b>Exact ZIP mode</b> — prefix ends with {@code .zip}: issues a HEAD request
 *       and downloads that specific object.</li>
 * </ul>
 *
 * <p>Environment variable expansion is handled by the caller before invoking this class.</p>
 */
public class S3ArchiveDownloader {

    private final S3Service s3Service;
    private final EndpointS3SCMConfigValidator configValidator;
    private final ChecksumVerifier checksumVerifier;

    /**
     * Creates downloader service.
     *
     * @param s3Service        S3 operation service
     * @param configValidator  runtime configuration validator
     * @param checksumVerifier checksum verification service
     */
    public S3ArchiveDownloader(S3Service s3Service,
                               EndpointS3SCMConfigValidator configValidator,
                               ChecksumVerifier checksumVerifier) {
        this.s3Service = s3Service;
        this.configValidator = configValidator;
        this.checksumVerifier = checksumVerifier;
    }

    /**
     * Downloads a ZIP archive from one S3 location.
     *
     * <p>The {@code expandedPrefix} must already have Jenkins environment variable
     * references resolved by the caller. The download mode (prefix vs exact) is
     * determined internally from the expanded value.</p>
     *
     * @param build            Jenkins build context (used for credential lookup)
     * @param location         S3 source location
     * @param expandedPrefix   already-expanded directory prefix or exact ZIP key
     * @param tempFile         temporary target file
     * @param listener         Jenkins task listener
     * @param config           resolved checkout configuration
     * @param credentialLookup credential lookup strategy
     * @return downloaded object metadata
     * @throws AbortException       if checkout cannot continue (invalid config, object not found, size exceeded)
     * @throws IOException          if an S3 or I/O operation fails
     * @throws InterruptedException if a retry sleep is interrupted
     */
    public S3ObjectCandidate downloadLatest(@NonNull Run<?, ?> build,
                                            @NonNull S3Location location,
                                            @NonNull String expandedPrefix,
                                            @NonNull Path tempFile,
                                            @NonNull TaskListener listener,
                                            @NonNull CheckoutConfig config,
                                            @NonNull BiFunction<Run<?, ?>, String, StandardUsernamePasswordCredentials> credentialLookup)
            throws IOException, AbortException, InterruptedException {

        StandardUsernamePasswordCredentials credentials =
                credentialLookup.apply(build, location.getCredentialsId());

        if (credentials == null) {
            throw new AbortException("Cannot find credentials: " + location.getCredentialsId());
        }

        configValidator.validateCredentials(credentials);

        try (S3Client s3 = s3Service.createClient(
                location.getEndpoint(),
                location.getRegion(),
                credentials.getUsername(),
                Secret.toString(credentials.getPassword())
        )) {
            PrintStream log = listener.getLogger();

            S3ObjectCandidate candidate = PluginUtils.isExactZipKey(expandedPrefix)
                    ? resolveExactZip(s3, location.getBucket(), expandedPrefix, log)
                    : resolveLatestZip(s3, location.getBucket(), expandedPrefix, log);

            log.printf(
                    LOG_PREFIX + "ZIP found: key='%s', size=%d bytes, lastModified=%s%n",
                    candidate.key(),
                    candidate.size(),
                    candidate.lastModified()
            );

            if (candidate.size() > MAX_ARCHIVE_SIZE_BYTES) {
                throw new AbortException(String.format(
                        "Archive too large (%d bytes). Max: %d",
                        candidate.size(),
                        MAX_ARCHIVE_SIZE_BYTES
                ));
            }

            Files.deleteIfExists(tempFile);
            log.println(LOG_PREFIX + "Downloading object...");

            s3Service.getObject(
                    s3,
                    location.getBucket(),
                    candidate.key(),
                    tempFile,
                    config.maxRetries(),
                    config.retryDelayMs(),
                    listener
            );

            if (!config.skipChecksumVerification()) {
                checksumVerifier.verify(s3, s3Service, location.getBucket(), candidate.key(), tempFile, log);
            } else {
                log.println(LOG_PREFIX + "Checksum verification skipped.");
            }

            return candidate;

        } catch (SdkException e) {
            throw new IOException("S3 operation failed: " + cleanErrorMessage(e), e);
        }
    }

    /**
     * Prefix mode: lists objects under the prefix and returns the latest ZIP by timestamp.
     *
     * @throws AbortException if no ZIP files are found
     */
    private S3ObjectCandidate resolveLatestZip(S3Client s3, String bucket, String prefix,
                                               PrintStream log) throws AbortException {
        log.printf(LOG_PREFIX + "Searching latest ZIP in bucket='%s', prefix='%s'%n", bucket, prefix);

        S3ObjectCandidate latest = s3Service.findLatestZipObject(s3, bucket, prefix);

        if (latest == null) {
            throw new AbortException("No ZIP files found in prefix: " + prefix);
        }

        return latest;
    }

    /**
     * Exact ZIP mode: issues a HEAD request to confirm the object exists and returns its metadata.
     *
     * @throws AbortException if the object does not exist at the normalized key
     */
    private S3ObjectCandidate resolveExactZip(S3Client s3, String bucket, String key,
                                              PrintStream log) throws AbortException {
        String normalizedKey = PluginUtils.normalizePrefix(key);
        log.printf(LOG_PREFIX + "Exact ZIP mode: key='%s'%n", normalizedKey);

        S3ObjectCandidate object = s3Service.headObject(s3, bucket, normalizedKey);

        if (object == null) {
            throw new AbortException("No ZIP file found at key: " + normalizedKey);
        }

        return object;
    }

    /**
     * Normalizes exception message for Jenkins build log output.
     */
    private String cleanErrorMessage(Exception e) {
        String message = e.getMessage();

        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }

        return message.replace(LOG_PREFIX, "").trim();
    }
}
