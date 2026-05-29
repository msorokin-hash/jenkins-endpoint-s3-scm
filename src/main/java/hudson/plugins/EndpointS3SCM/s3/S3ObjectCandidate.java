package hudson.plugins.EndpointS3SCM.s3;

import java.time.Instant;

/**
 * Represents an S3 object selected as a download candidate.
 * Used when the plugin searches for the latest ZIP archive in a configured S3 prefix.
 */
public final class S3ObjectCandidate {

    private final String key;
    private final long size;
    private final Instant lastModified;

    public S3ObjectCandidate(String key, long size, Instant lastModified) {
        this.key = key;
        this.size = size;
        this.lastModified = lastModified;
    }

    public String key() {
        return this.key;
    }

    public long size() {
        return this.size;
    }

    public Instant lastModified() {
        return this.lastModified;
    }
}
