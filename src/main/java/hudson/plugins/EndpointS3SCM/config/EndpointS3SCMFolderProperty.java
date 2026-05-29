package hudson.plugins.EndpointS3SCM.config;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Run;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.*;
import static hudson.plugins.EndpointS3SCM.config.EndpointS3SCMSettingsSupport.clamp;
import static hudson.plugins.EndpointS3SCM.config.EndpointS3SCMSettingsSupport.normalizeLocations;

/**
 * Folder-level S3 SCM configuration property.
 *
 * <p>Configured per-folder via the folder configuration page.</p>
 *
 * <p>Jobs inside a folder inherit settings from the nearest ancestor folder
 * that has this property configured.</p>
 *
 * <h3>Lookup strategy</h3>
 * <ul>
 *   <li>Walk up the parent folder chain starting from the job's direct parent.</li>
 *   <li>Return the first {@link AbstractFolder} that has this property configured.</li>
 *   <li>A nested folder fully overrides its parent settings — no merging is performed.</li>
 * </ul>
 */
public class EndpointS3SCMFolderProperty
        extends AbstractFolderProperty<AbstractFolder<?>>
        implements EndpointS3SCMSettings {

    private List<S3Location> locations = new ArrayList<>();
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private int retryDelayMs = DEFAULT_RETRY_DELAY_MS;
    private boolean stripTopLevelDir = DEFAULT_STRIP_TOP_LEVEL_DIR;
    private boolean skipChecksumVerification = DEFAULT_SKIP_CHECKSUM_VERIFICATION;

    @DataBoundConstructor
    public EndpointS3SCMFolderProperty() {
    }

    /**
     * Finds the nearest ancestor folder that has {@link EndpointS3SCMFolderProperty} configured.
     *
     * @param run build whose job hierarchy to traverse; may be {@code null}
     * @return nearest folder property, or {@code null} if none found
     */
    @Nullable
    public static EndpointS3SCMFolderProperty findNearest(@Nullable Run<?, ?> run) {
        if (run == null) {
            return null;
        }

        return findNearest(run.getParent());
    }

    /**
     * Finds the nearest ancestor folder that has {@link EndpointS3SCMFolderProperty} configured.
     *
     * @param job job whose parent hierarchy to traverse; may be {@code null}
     * @return nearest folder property, or {@code null} if none found
     */
    @Nullable
    public static EndpointS3SCMFolderProperty findNearest(@Nullable Job<?, ?> job) {
        if (job == null) {
            return null;
        }

        return walkUp(job.getParent());
    }

    /**
     * Walks up the {@link ItemGroup} hierarchy looking for the nearest
     * {@link AbstractFolder} that has this property configured.
     *
     * <p>Traversal stops at the Jenkins root.</p>
     *
     * @param start starting parent item group
     * @return nearest folder property, or {@code null} if none found
     */
    @Nullable
    private static EndpointS3SCMFolderProperty walkUp(@Nullable ItemGroup<?> start) {
        ItemGroup<?> current = start;

        while (current instanceof hudson.model.Item item) {

            if (item instanceof AbstractFolder<?> folder) {
                EndpointS3SCMFolderProperty prop =
                        folder.getProperties().get(EndpointS3SCMFolderProperty.class);

                if (prop != null) {
                    return prop;
                }
            }

            current = item.getParent();
        }

        return null;
    }

    @Override
    public List<S3Location> getLocations() {
        return locations != null
                ? Collections.unmodifiableList(locations)
                : Collections.emptyList();
    }

    @DataBoundSetter
    public void setLocations(List<S3Location> locations) {
        this.locations = normalizeLocations(locations);
    }

    @Override
    public int getMaxRetries() {
        return maxRetries;
    }

    @DataBoundSetter
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = clamp(maxRetries, MIN_MAX_RETRIES, MAX_MAX_RETRIES);
    }

    @Override
    public int getRetryDelayMs() {
        return retryDelayMs;
    }

    @DataBoundSetter
    public void setRetryDelayMs(int retryDelayMs) {
        this.retryDelayMs = clamp(retryDelayMs, MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS);
    }

    @Override
    public boolean isStripTopLevelDir() {
        return stripTopLevelDir;
    }

    @DataBoundSetter
    public void setStripTopLevelDir(boolean stripTopLevelDir) {
        this.stripTopLevelDir = stripTopLevelDir;
    }

    @Override
    public boolean isSkipChecksumVerification() {
        return skipChecksumVerification;
    }

    @DataBoundSetter
    public void setSkipChecksumVerification(boolean skipChecksumVerification) {
        this.skipChecksumVerification = skipChecksumVerification;
    }

    /**
     * Restores safe defaults after Jenkins deserializes the configuration.
     *
     * @return this configuration instance
     */
    protected Object readResolve() {
        locations = normalizeLocations(locations);
        maxRetries = clamp(maxRetries, MIN_MAX_RETRIES, MAX_MAX_RETRIES);
        retryDelayMs = clamp(retryDelayMs, MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS);
        return this;
    }

    @Extension
    public static class DescriptorImpl extends AbstractFolderPropertyDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Endpoint S3 SCM Settings";
        }
    }
}
