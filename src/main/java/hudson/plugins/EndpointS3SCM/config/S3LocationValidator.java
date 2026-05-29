package hudson.plugins.EndpointS3SCM.config;

import hudson.AbortException;

import static hudson.plugins.EndpointS3SCM.util.PluginUtils.isEmpty;
import static java.net.URI.create;

/**
 * Validates {@link S3Location} values used by UI connection tests.
 */
public class S3LocationValidator {

    /**
     * Validates S3 location before running connection test.
     *
     * @param location S3 location
     * @throws AbortException if location is invalid
     */
    public void validateForConnectionTest(S3Location location) throws AbortException {
        if (location == null) {
            throw new AbortException("Location is empty.");
        }

        if (isEmpty(location.getEndpoint())) {
            throw new AbortException("Endpoint is required.");
        }

        if (isEmpty(location.getBucket())) {
            throw new AbortException("Bucket is required.");
        }

        if (isEmpty(location.getCredentialsId())) {
            throw new AbortException("Credentials are required.");
        }

        try {
            create(location.getEndpoint());
        } catch (Exception e) {
            throw new AbortException("Invalid endpoint URL: " + location.getEndpoint());
        }
    }
}
