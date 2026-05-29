package hudson.plugins.EndpointS3SCM.checkout;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.model.Action;
import hudson.model.EnvironmentContributingAction;
import hudson.model.Run;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Locale;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.*;

/**
 * Build action that stores S3 artifact metadata from a successful checkout.
 *
 * <p>Attached to the build after checkout completes successfully. Serves two
 * purposes:</p>
 * <ol>
 *   <li>Contributes post-checkout environment variables to subsequent pipeline
 *       steps via {@link EnvironmentContributingAction}.</li>
 *   <li>Shows an artifact summary on the build page via {@code summary.jelly}.</li>
 * </ol>
 *
 * <h2>Exported environment variables</h2>
 * <table>
 *   <tr><th>Variable</th><th>Content</th></tr>
 *   <tr><td>{@code ENDPOINT_S3_KEY}</td><td>Actual S3 object key downloaded</td></tr>
 *   <tr><td>{@code ENDPOINT_S3_BUCKET}</td><td>Bucket of the successful source</td></tr>
 *   <tr><td>{@code ENDPOINT_S3_SOURCE}</td><td>Name of the successful S3 location</td></tr>
 *   <tr><td>{@code ENDPOINT_S3_LAST_MODIFIED}</td><td>ISO-8601 last-modified timestamp</td></tr>
 *   <tr><td>{@code ENDPOINT_S3_SIZE}</td><td>Archive size in bytes</td></tr>
 *   <tr><td>{@code ENDPOINT_S3_MODE}</td><td>{@code LATEST} or {@code EXACT}</td></tr>
 * </table>
 */
public class S3CheckoutAction implements Action, EnvironmentContributingAction, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String key;
    private final String bucket;
    private final String sourceName;
    /**
     * Epoch milliseconds; {@code -1} when the timestamp is unknown.
     */
    private final long lastModifiedMs;
    private final long size;
    /**
     * {@code "LATEST"} or {@code "EXACT"}.
     */
    private final String mode;

    public S3CheckoutAction(String key, String bucket, String sourceName,
                            Instant lastModified, long size, String mode) {
        this.key = key;
        this.bucket = bucket;
        this.sourceName = sourceName;
        this.lastModifiedMs = lastModified != null ? lastModified.toEpochMilli() : -1L;
        this.size = size;
        this.mode = mode;
    }

    public String getKey() {
        return key;
    }

    public String getBucket() {
        return bucket;
    }

    public String getSourceName() {
        return sourceName;
    }

    public long getLastModifiedMs() {
        return lastModifiedMs;
    }

    public long getSize() {
        return size;
    }

    public String getMode() {
        return mode;
    }

    /**
     * Returns the last-modified instant, or {@code null} if unknown.
     */
    public Instant getLastModified() {
        return lastModifiedMs > 0 ? Instant.ofEpochMilli(lastModifiedMs) : null;
    }

    /**
     * Returns an ISO-8601 string for the last-modified timestamp, or {@code "—"} when
     * the timestamp is unavailable.
     * Called from {@code summary.jelly}.
     */
    public String getLastModifiedForDisplay() {
        return lastModifiedMs > 0 ? Instant.ofEpochMilli(lastModifiedMs).toString() : "—";
    }

    /**
     * Returns a human-readable file size (B / KB / MB).
     * Called from {@code summary.jelly}.
     */
    public String getFormattedSize() {
        if (size < 1024L) return size + " B";
        if (size < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024));
    }

    @Override
    public String getIconFileName() {
        return "/plugin/endpoint-s3-scm-plugin/logo.svg";
    }

    @Override
    public String getDisplayName() {
        return "S3 Artifact";
    }

    @Override
    public String getUrlName() {
        return "s3-artifact";
    }

    /**
     * Contributes post-checkout env vars to any {@link Run} — including Pipeline jobs.
     * Called automatically by Jenkins when environment variables are evaluated.
     */
    @Override
    public void buildEnvironment(@NonNull Run<?, ?> build, @NonNull EnvVars env) {
        if (key != null) env.put(ENV_KEY, key);
        if (bucket != null) env.put(ENV_BUCKET, bucket);
        if (sourceName != null) env.put(ENV_SOURCE, sourceName);
        if (lastModifiedMs > 0) env.put(ENV_LAST_MODIFIED, Instant.ofEpochMilli(lastModifiedMs).toString());
        env.put(ENV_SIZE, String.valueOf(size));
        if (mode != null) env.put(ENV_MODE, mode);
    }

}
