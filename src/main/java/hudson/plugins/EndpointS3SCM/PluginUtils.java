package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.Item;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import software.amazon.awssdk.regions.Region;

import java.util.Collections;

import static java.net.URI.create;

/**
 * Utility helper class containing common reusable methods
 * used across the S3 Endpoint SCM plugin.
 * <p>
 * Includes helpers for:
 * <ul>
 *     <li>String validation</li>
 *     <li>Safe URL logging (masking credentials)</li>
 *     <li>S3 region resolution with fallback</li>
 *     <li>S3 prefix normalization</li>
 * </ul>
 */
public final class PluginUtils {

    /**
     * Default AWS region used when region is not specified
     * or cannot be parsed.
     * <p>
     * This is required because AWS SDK v2 expects a region
     * even for S3-compatible storages like MinIO or Ceph.
     */
    public static final Region DEFAULT_REGION = Region.US_EAST_1;

    /**
     * Private constructor to prevent instantiation - this is a utility class.
     */
    private PluginUtils() {
    }

    /**
     * Checks if a string is null, empty, or contains only whitespace characters.
     *
     * @param s The string to check
     * @return true if the string is null, empty, or whitespace-only; false otherwise
     */
    public static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Masks sensitive authentication information (username:password) in a URL string.
     * This is useful for logging URLs without exposing credentials.
     * <p>
     * Example:
     * <pre>
     * https://user:pass@example.com -> https://****@example.com
     * </pre>
     *
     * @param url The URL string that may contain user authentication information
     * @return The URL with authentication credentials masked as "****",
     * or the original URL if no credentials were found or parsing failed
     */
    public static String maskUrl(String url) {
        if (url == null) return "";
        try {
            var uri = create(url);
            if (uri.getUserInfo() != null) {
                return url.replace(uri.getUserInfo() + "@", "****@");
            }
        } catch (Exception ignored) {
            // If URL parsing fails, return the original URL unchanged
            // This ensures we don't break logging even with malformed URLs
        }
        return url;
    }

    /**
     * Resolves AWS region from a string value.
     * <p>
     * If the provided region is null, empty, or invalid,
     * a default region {@link #DEFAULT_REGION} is returned.
     * <p>
     * This is especially important for S3-compatible storages
     * that may not strictly require region validation.
     *
     * @param region Region string (may be null or invalid)
     * @return Valid AWS Region object
     */
    public static Region resolveRegion(String region) {
        if (isEmpty(region)) {
            return DEFAULT_REGION;
        }

        try {
            return Region.of(region);
        } catch (Exception ignored) {
            return DEFAULT_REGION;
        }
    }

    /**
     * Resolves region identifier string for logging purposes.
     * <p>
     * Always returns a valid region ID even if the input is invalid.
     *
     * @param region Raw region string
     * @return Region ID string (e.g. "us-east-1")
     */
    public static String resolveRegionId(String region) {
        return resolveRegion(region).id();
    }

    /**
     * Normalizes S3 prefix (directory path).
     * <p>
     * Removes leading slashes because S3 object keys are not absolute paths.
     * <p>
     * Example:
     * <pre>
     * "/releases/" -> "releases/"
     * </pre>
     *
     * @param prefix Raw prefix string
     * @return Normalized prefix or empty string if null/blank
     */
    public static String normalizePrefix(String prefix) {
        if (isEmpty(prefix)) {
            return "";
        }

        String normalized = prefix.trim();

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        return normalized;
    }

    /**
     * Returns a ListBoxModel of available StandardUsernamePasswordCredentials for the given Jenkins context with permission checks.
     * <p>
     * Performs access control: requires admin rights for global context or EXTENDED_READ/USE_ITEM permissions for item context.
     * Includes all matching credentials and preserves current selection.
     *
     * @param context Jenkins item context (may be null for global access)
     * @param credentialsId Currently selected credential ID to preserve
     * @return ListBoxModel with available credentials or empty model if access denied
     */
    static ListBoxModel getOptions(@AncestorInPath Item context, @QueryParameter String credentialsId) {
        StandardListBoxModel options = new StandardListBoxModel();

        if (context == null) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return options;
            }
        } else if (!context.hasPermission(Item.EXTENDED_READ)
                && !context.hasPermission(CredentialsProvider.USE_ITEM)) {
            return options;
        }

        return options
                .withEmptySelection()
                .withMatching(
                        CredentialsMatchers.always(),
                        CredentialsProvider.lookupCredentials(
                                StandardUsernamePasswordCredentials.class,
                                context,
                                null,
                                Collections.emptyList()
                        )
                )
                .includeCurrentValue(credentialsId);
    }
}