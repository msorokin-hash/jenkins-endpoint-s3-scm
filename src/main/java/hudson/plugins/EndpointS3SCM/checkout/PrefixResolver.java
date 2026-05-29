package hudson.plugins.EndpointS3SCM.checkout;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.util.PluginUtils;

import java.io.IOException;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.LOG_PREFIX;

/**
 * Resolves the job-level Directory / Prefix value into an effective S3 lookup expression.
 *
 * <p>The resolver expands Jenkins environment variables, such as
 * {@code ${BRANCH_NAME}}, and determines the checkout mode:</p>
 *
 * <ul>
 *   <li>{@code EXACT} — expanded value ends with {@code .zip}; the value is treated as
 *       a direct S3 object key.</li>
 *   <li>{@code LATEST} — expanded value does not end with {@code .zip}; the value is
 *       treated as an S3 prefix and the latest ZIP under that prefix is selected.</li>
 * </ul>
 */
public class PrefixResolver {

    /**
     * Resolves and classifies the given prefix.
     *
     * @param build    current build; may be {@code null} in unit tests
     * @param prefix   raw Directory / Prefix value from job configuration
     * @param listener Jenkins task listener
     * @return resolved prefix metadata
     */
    public ResolvedPrefix resolve(@Nullable Run<?, ?> build,
                                  @Nullable String prefix,
                                  @NonNull TaskListener listener) {
        String original = prefix != null ? prefix : "";
        String expanded = expand(build, original, listener);
        String mode = PluginUtils.isExactZipKey(expanded) ? "EXACT" : "LATEST";

        return new ResolvedPrefix(original, expanded, mode);
    }

    /**
     * Expands Jenkins environment variable references in the prefix string.
     *
     * @param build    current build; may be {@code null}
     * @param prefix   raw prefix value
     * @param listener Jenkins task listener
     * @return expanded prefix, or original prefix if expansion is not possible
     */
    private String expand(@Nullable Run<?, ?> build,
                          @NonNull String prefix,
                          @NonNull TaskListener listener) {
        if (build == null || prefix.isBlank()) {
            return prefix;
        }

        try {
            return build.getEnvironment(listener).expand(prefix);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.getLogger().printf(
                    LOG_PREFIX + "Warning: could not expand env vars in prefix '%s': %s%n",
                    prefix,
                    e.getMessage()
            );
            return prefix;
        } catch (IOException e) {
            listener.getLogger().printf(
                    LOG_PREFIX + "Warning: could not expand env vars in prefix '%s': %s%n",
                    prefix,
                    e.getMessage()
            );
            return prefix;
        }
    }

    /**
     * Resolved Directory / Prefix value.
     *
     * @param original raw value from job configuration
     * @param expanded value after Jenkins environment variable expansion
     * @param mode     {@code EXACT} or {@code LATEST}
     */
    public record ResolvedPrefix(String original, String expanded, String mode) {

        /**
         * Returns whether the resolved prefix points to a concrete ZIP object key.
         *
         * @return {@code true} for exact ZIP mode
         */
        public boolean isExact() {
            return "EXACT".equals(mode);
        }
    }
}
