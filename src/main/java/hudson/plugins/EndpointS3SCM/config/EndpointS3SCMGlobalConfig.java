package hudson.plugins.EndpointS3SCM.config;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.*;
import static hudson.plugins.EndpointS3SCM.config.EndpointS3SCMSettingsSupport.clamp;
import static hudson.plugins.EndpointS3SCM.config.EndpointS3SCMSettingsSupport.normalizeLocations;

/**
 * Global fallback configuration for the Endpoint S3 SCM plugin.
 *
 * <p>Appears in <strong>Manage Jenkins → System</strong> under the
 * "Endpoint S3 SCM — Global Settings" section.</p>
 *
 * <p>This configuration is used only when a job has no parent folder
 * with {@link EndpointS3SCMFolderProperty} configured — for example,
 * jobs at the Jenkins root level or code running from a Global Shared Library
 * where there is no folder context.</p>
 *
 * <h3>Resolution priority in CheckoutConfigResolver</h3>
 * <ol>
 *   <li>Test override (unit tests only)</li>
 *   <li>Nearest ancestor {@link EndpointS3SCMFolderProperty} — wins completely</li>
 *   <li>This global configuration — fallback when no folder property is found</li>
 *   <li>Empty defaults — validator will abort with a clear error message</li>
 * </ol>
 */
@Extension
public class EndpointS3SCMGlobalConfig extends GlobalConfiguration implements EndpointS3SCMSettings {

    private List<S3Location> locations = new ArrayList<>();
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private int retryDelayMs = DEFAULT_RETRY_DELAY_MS;
    private boolean stripTopLevelDir = DEFAULT_STRIP_TOP_LEVEL_DIR;
    private boolean skipChecksumVerification = DEFAULT_SKIP_CHECKSUM_VERIFICATION;

    public EndpointS3SCMGlobalConfig() {
        load();
    }

    /**
     * Returns the singleton instance registered by Jenkins.
     *
     * @return global config instance, or {@code null} if not yet loaded
     */
    @Nullable
    public static EndpointS3SCMGlobalConfig get() {
        return GlobalConfiguration.all().get(EndpointS3SCMGlobalConfig.class);
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "Endpoint S3 SCM — Global Settings";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }

    @Override
    public List<S3Location> getLocations() {
        return locations != null ? Collections.unmodifiableList(locations) : Collections.emptyList();
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
}
