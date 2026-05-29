package hudson.plugins.EndpointS3SCM;

import software.amazon.awssdk.regions.Region;

/**
 * Shared constants used by the Endpoint S3 SCM plugin.
 */
public final class EndpointS3SCMConstants {

    /**
     * Common prefix for Jenkins build log messages.
     */
    public static final String LOG_PREFIX = "[EndpointS3SCM] ";

    /**
     * Temporary file prefix for downloaded archives.
     */
    public static final String TEMP_FILE_PREFIX = "endpoint-s3scm-";

    /**
     * Temporary file suffix for downloaded archives.
     */
    public static final String ZIP_FILE_SUFFIX = ".zip";

    /**
     * Maximum allowed archive size before download.
     */
    public static final long MAX_ARCHIVE_SIZE_BYTES = 1_000_000_000L;

    /**
     * Default download retry attempts.
     */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * Minimum allowed download retry attempts.
     */
    public static final int MIN_MAX_RETRIES = 1;

    /**
     * Maximum allowed download retry attempts.
     */
    public static final int MAX_MAX_RETRIES = 10;

    /**
     * Default retry delay in milliseconds.
     */
    public static final int DEFAULT_RETRY_DELAY_MS = 1000;

    /**
     * Minimum allowed retry delay in milliseconds.
     */
    public static final int MIN_RETRY_DELAY_MS = 500;

    /**
     * Maximum allowed retry delay in milliseconds.
     */
    public static final int MAX_RETRY_DELAY_MS = 30000;

    /**
     * Default strip top-level directory option.
     */
    public static final boolean DEFAULT_STRIP_TOP_LEVEL_DIR = true;

    /**
     * Default checksum verification skip option.
     */
    public static final boolean DEFAULT_SKIP_CHECKSUM_VERIFICATION = false;

    /**
     * Buffer size used for ZIP extraction streams.
     */
    public static final int ZIP_BUFFER_SIZE = 8192;

    /**
     * Maximum total extracted archive size in bytes.
     */
    public static final long MAX_EXTRACTED_SIZE_BYTES = 500L * 1024 * 1024;

    /**
     * Maximum extracted single file size in bytes.
     */
    public static final long MAX_EXTRACTED_FILE_SIZE_BYTES = 100L * 1024 * 1024;

    /**
     * Maximum allowed number of extracted files.
     */
    public static final int MAX_EXTRACTED_FILES = 20_000;

    /**
     * Default AWS region used when region is not specified
     * or cannot be parsed.
     */
    public static final Region DEFAULT_REGION = Region.US_EAST_1;

    /**
     * Actual S3 object key that was downloaded (expanded; exact key, not a prefix template).
     */
    public static final String ENV_KEY = "ENDPOINT_S3_KEY";

    /**
     * Bucket of the S3 source that succeeded.
     */
    public static final String ENV_BUCKET = "ENDPOINT_S3_BUCKET";

    /**
     * Human-readable name of the S3 source location that succeeded.
     */
    public static final String ENV_SOURCE = "ENDPOINT_S3_SOURCE";

    /**
     * ISO-8601 last-modified timestamp of the downloaded object.
     */
    public static final String ENV_LAST_MODIFIED = "ENDPOINT_S3_LAST_MODIFIED";

    /**
     * Size of the downloaded archive in bytes.
     */
    public static final String ENV_SIZE = "ENDPOINT_S3_SIZE";

    /**
     * Download mode: {@code LATEST} (latest ZIP found under the prefix)
     * or {@code EXACT} (specific key ending with {@code .zip}).
     */
    public static final String ENV_MODE = "ENDPOINT_S3_MODE";

    private EndpointS3SCMConstants() {
    }
}