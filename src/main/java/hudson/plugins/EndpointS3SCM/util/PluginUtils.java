package hudson.plugins.EndpointS3SCM.util;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import software.amazon.awssdk.regions.Region;

import java.util.Collections;
import java.util.Locale;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.DEFAULT_REGION;
import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.ZIP_FILE_SUFFIX;
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

    private PluginUtils() {
    }

    /**
     * Checks if a string is null, empty, or contains only whitespace characters.
     */
    public static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Masks sensitive authentication information (username:password) in a URL string.
     */
    public static String maskUrl(String url) {
        if (url == null) return "";
        try {
            var uri = create(url);
            if (uri.getUserInfo() != null) {
                return url.replace(uri.getUserInfo() + "@", "****@");
            }
        } catch (Exception ignored) {
        }
        return url;
    }

    /**
     * Resolves AWS region from a string value, falling back to the default region.
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
     */
    public static String resolveRegionId(String region) {
        return resolveRegion(region).id();
    }

    /**
     * Returns {@code true} if the given value represents a direct S3 ZIP object key
     * (ends with {@code .zip}, case-insensitive).
     */
    public static boolean isExactZipKey(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).endsWith(ZIP_FILE_SUFFIX);
    }

    /**
     * Normalizes S3 prefix — removes leading slashes.
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
     * Returns a ListBoxModel of available credentials for the given Jenkins context.
     */
    public static ListBoxModel getOptions(@AncestorInPath Item context,
                                          @QueryParameter String credentialsId) {
        StandardListBoxModel items = new StandardListBoxModel();

        if (context == null) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return items.includeCurrentValue(credentialsId);
            }
            return items
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            StandardUsernamePasswordCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always()
                    )
                    .includeCurrentValue(credentialsId);
        }

        if (!context.hasPermission(Item.CONFIGURE)
                && !context.hasPermission(CredentialsProvider.USE_ITEM)) {
            return items.includeCurrentValue(credentialsId);
        }

        return items
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        context,
                        StandardUsernamePasswordCredentials.class,
                        Collections.emptyList(),
                        CredentialsMatchers.always()
                )
                .includeCurrentValue(credentialsId);
    }
}
