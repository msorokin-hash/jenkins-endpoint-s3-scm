package hudson.plugins.EndpointS3SCM;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Job;
import hudson.scm.SCMDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;

import java.util.regex.Pattern;

import static hudson.plugins.EndpointS3SCM.PluginUtils.getOptions;
import static hudson.plugins.EndpointS3SCM.PluginUtils.isEmpty;
import static java.net.URI.create;

/**
 * Jenkins descriptor for {@link EndpointS3SCM}.
 *
 * <p>Provides UI integration: display name, field validation, and credential dropdown.</p>
 */
@Extension
@Symbol("endpointS3scm")
public class EndpointS3SCMDescriptor extends SCMDescriptor<EndpointS3SCM> {

    private static final Pattern REGION_PATTERN = Pattern.compile("^[A-Za-z0-9-]+$");

    /**
     * Constructor loads persisted configuration.
     */
    public EndpointS3SCMDescriptor() {
        super(EndpointS3SCM.class, null);
        load();
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
     * Populates credentials dropdown.
     */
    public ListBoxModel doFillCredentialsIdItems(
            @AncestorInPath Item context,
            @QueryParameter String credentialsId
    ) {
        return getOptions(context, credentialsId);
    }

    /**
     * Validates S3 endpoint URL format.
     */
    public FormValidation doCheckEndpoint(@QueryParameter String value) {
        String endpoint = Util.fixEmptyAndTrim(value);
        if (endpoint == null) {
            return FormValidation.error("Please enter endpoint URL.");
        }
        try {
            create(endpoint);
            return FormValidation.ok();
        } catch (Exception e) {
            return FormValidation.error("Invalid endpoint URL.");
        }
    }

    /**
     * Validates prefix field – must not be empty.
     */
    public FormValidation doCheckPrefix(@QueryParameter String value) {
        return isEmpty(Util.fixEmptyAndTrim(value))
                ? FormValidation.error("Please enter S3 directory/prefix.")
                : FormValidation.ok();
    }

    /**
     * Validates region field – allows letters, numbers, and dashes.
     */
    public FormValidation doCheckRegion(@QueryParameter String value) {
        String region = Util.fixEmptyAndTrim(value);
        if (region == null) {
            return FormValidation.ok();
        }
        if (!REGION_PATTERN.matcher(region).matches()) {
            return FormValidation.error("Region may contain only letters, numbers and dashes.");
        }
        return FormValidation.ok();
    }
}