package hudson.plugins.EndpointS3SCM;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Job;
import hudson.scm.SCMDescriptor;
import hudson.util.FormValidation;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.QueryParameter;

import static hudson.plugins.EndpointS3SCM.util.PluginUtils.isEmpty;

/**
 * Jenkins descriptor for {@link EndpointS3SCM}.
 *
 * <p>Responsible only for per-job UI (directory/prefix field) and its validation.
 * S3 source settings are resolved during checkout from the nearest ancestor
 * {@link EndpointS3SCMFolderProperty}, with {@link EndpointS3SCMGlobalConfig}
 * used as a fallback for root-level jobs and Global Shared Libraries.</p>
 *
 * <p>Credentials and region validation live in {@link S3Location.DescriptorImpl}
 * where those fields are actually rendered.</p>
 */
@Extension
@Symbol("endpointS3scm")
public class EndpointS3SCMDescriptor extends SCMDescriptor<EndpointS3SCM> {

    public EndpointS3SCMDescriptor() {
        super(EndpointS3SCM.class, null);
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "S3";
    }

    @Override
    public boolean isApplicable(Job project) {
        return true;
    }

    /**
     * Validates prefix field — must not be empty.
     */
    public FormValidation doCheckPrefix(@QueryParameter String value) {
        return isEmpty(Util.fixEmptyAndTrim(value))
                ? FormValidation.error("Please enter S3 directory/prefix.")
                : FormValidation.ok();
    }
}
