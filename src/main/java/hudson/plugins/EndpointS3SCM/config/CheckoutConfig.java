package hudson.plugins.EndpointS3SCM.config;

import java.util.List;

/**
 * Immutable snapshot of all checkout-relevant settings resolved for a single build.
 *
 * @param locations                S3 source locations sorted by priority
 * @param maxRetries               maximum number of download retry attempts
 * @param retryDelayMs             base retry delay in milliseconds
 * @param stripTopLevelDir         whether to strip common top-level ZIP directory
 * @param skipChecksumVerification whether checksum verification should be skipped
 */
public record CheckoutConfig(
        List<S3Location> locations,
        int maxRetries,
        int retryDelayMs,
        boolean stripTopLevelDir,
        boolean skipChecksumVerification
) {
}
