package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Job;
import hudson.scm.SCMDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Collections;
import java.util.List;

import static hudson.plugins.EndpointS3SCM.PluginUtils.isEmpty;
import static java.net.URI.create;

@Extension
@Symbol("endpointS3scm")
public class EndpointS3SCMDescriptor extends SCMDescriptor<EndpointS3SCM> {

    private final S3Service s3Service = new S3Service();

    /**
     * Constructor for the SCM descriptor.
     * Initializes the descriptor and loads saved configuration.
     */
    public EndpointS3SCMDescriptor() {
        super(EndpointS3SCM.class, null);
        load();
    }

    /**
     * Returns the display name for this SCM implementation.
     * This appears in the Jenkins job configuration UI.
     *
     * @return Display name "S3"
     */
    @NonNull
    @Override
    public String getDisplayName() {
        return "S3";
    }

    /**
     * Determines if this SCM is applicable to a given project.
     *
     * @param project The Jenkins job to check
     * @return Always true, meaning this SCM can be used with any job
     */
    @Override
    public boolean isApplicable(Job project) {
        return true;
    }

    /**
     * Populates the credentials dropdown list in the Jenkins UI.
     * This method is called via AJAX to fill the credentials selection box.
     *
     * @param context       The Jenkins item (job/folder) context
     * @param credentialsId Currently selected credentials ID
     * @return ListBoxModel containing available credentials for selection
     */
    public ListBoxModel doFillCredentialsIdItems(
            @AncestorInPath Item context,
            @QueryParameter String credentialsId
    ) {
        // Check permissions
        if (context == null) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new ListBoxModel();
            }
        } else {
            if (!context.hasPermission(Item.EXTENDED_READ)
                    && !context.hasPermission(CredentialsProvider.USE_ITEM)) {
                return new ListBoxModel();
            }
        }

        List<DomainRequirement> domainRequirements = Collections.emptyList();

        // Retrieve all available credentials in the current context
        ListBoxModel creds = CredentialsProvider.listCredentialsInItem(
                StandardUsernamePasswordCredentials.class,
                context,
                null,
                domainRequirements,
                CredentialsMatchers.always()
        );

        StandardListBoxModel model = new StandardListBoxModel();
        model.addAll(creds);
        model.includeCurrentValue(credentialsId);

        return model;
    }

    /**
     * Validates the S3 endpoint URL input field.
     * Called when user enters/changes the endpoint URL in the Jenkins UI.
     *
     * @param value The endpoint URL to validate
     * @return FormValidation with OK status or error message
     */
    public FormValidation doCheckEndpoint(@QueryParameter String value) {
        if (value == null || value.trim().isEmpty()) {
            return FormValidation.error("Please enter endpoint URL.");
        }

        try {
            create(value.trim()); // Validate URL syntax
        } catch (Exception e) {
            return FormValidation.error("Invalid endpoint URL.");
        }

        return FormValidation.ok();
    }

    /**
     * Validates the AWS region input field.
     * Checks if the region format is valid and warns about non-standard regions.
     *
     * @param value The region string to validate
     * @return FormValidation with OK, warning, or error status
     */
    public FormValidation doCheckRegion(@QueryParameter String value) {
        if (value == null || value.trim().isEmpty()) {
            return FormValidation.ok(); // Region is optional
        }

        String region = value.trim();

        if (!region.matches("^[A-Za-z0-9-]+$")) {
            return FormValidation.error("Region may contain only letters, numbers and dashes.");
        }

        try {
            Region.of(region); // Validate against AWS SDK's known regions
        } catch (Exception e) {
            return FormValidation.warning(
                    "Region is non-standard. Make sure your S3 endpoint accepts it."
            );
        }

        return FormValidation.ok();
    }

    /**
     * Tests the connection to S3 and verifies object accessibility.
     * This is the "Test Connection" button functionality in Jenkins UI.
     *
     * @param context       Jenkins item context for permission checking
     * @param endpoint      S3 endpoint URL
     * @param bucket        S3 bucket name
     * @param key           S3 object key
     * @param credentialsId Jenkins credentials ID for authentication
     * @param region        AWS region (optional)
     * @return FormValidation with connection test results
     */
    public FormValidation doTestConnection(
            @AncestorInPath Item context,
            @QueryParameter String endpoint,
            @QueryParameter String bucket,
            @QueryParameter String key,
            @QueryParameter String credentialsId,
            @QueryParameter String region
    ) {
        // Permission check - only users with CONFIGURE permission can test
        if (context == null || !context.hasPermission(Item.CONFIGURE)) {
            return FormValidation.ok();
        }

        // Clean and validate input parameters
        endpoint = Util.fixEmptyAndTrim(endpoint);
        bucket = Util.fixEmptyAndTrim(bucket);
        key = Util.fixEmptyAndTrim(key);
        credentialsId = Util.fixEmptyAndTrim(credentialsId);
        region = Util.fixEmptyAndTrim(region);

        if (isEmpty(endpoint) || isEmpty(bucket) || isEmpty(key) || isEmpty(credentialsId)) {
            return FormValidation.error("Endpoint, bucket, key and credentials are required");
        }

        try {
            create(endpoint); // Validate endpoint URL syntax
        } catch (Exception e) {
            return FormValidation.error("Invalid endpoint URL: " + endpoint);
        }

        // Look up credentials
        StandardUsernamePasswordCredentials creds =
                CredentialsHelper.lookupCredentialsForItem(context, credentialsId);

        if (creds == null) {
            return FormValidation.error("Cannot find credentials with id: " + credentialsId);
        }

        // Extract access key and secret from credentials
        String accessKey = creds.getUsername();
        String secretKey = creds.getPassword().getPlainText();

        if (isEmpty(accessKey) || isEmpty(secretKey)) {
            return FormValidation.error("Selected credentials are missing username or password");
        }

        try {
            // Test connection by attempting to retrieve object metadata
            HeadObjectResponse resp = s3Service.headObject(endpoint,
                    region,
                    bucket,
                    key,
                    accessKey,
                    secretKey);
            long size = resp.contentLength();

            String msg = String.format("Connection OK, object found, size: %d bytes", size);
            if (size > EndpointS3SCM.LARGE_OBJECT_WARNING_SIZE) {
                msg += " (large object)";
            }
            return FormValidation.ok(msg);
        } catch (NoSuchKeyException e) {
            return FormValidation.error("Connection OK, but object not found: " + key);
        } catch (S3Exception e) {
            return FormValidation.error("S3 error: " + e.awsErrorDetails().errorMessage());
        } catch (SdkException e) {
            return FormValidation.error("Client error: " + e.getMessage());
        } catch (Exception e) {
            return FormValidation.error("Failed to connect: " + e.getMessage());
        }
    }
}