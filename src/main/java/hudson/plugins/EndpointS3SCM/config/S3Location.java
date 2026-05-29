package hudson.plugins.EndpointS3SCM.config;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.plugins.EndpointS3SCM.checkout.CredentialsHelper;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.regex.Pattern;

import static hudson.plugins.EndpointS3SCM.util.PluginUtils.getOptions;
import static hudson.plugins.EndpointS3SCM.util.PluginUtils.isEmpty;

/**
 * Represents a single S3-compatible storage location.
 *
 * <p>A location defines a specific S3 endpoint, bucket, prefix and credentials
 * used during checkout.</p>
 *
 * <p>This class is used inside EndpointS3SCM and may be configured multiple times
 * to support failover and priority-based source selection.</p>
 *
 * <p>Credentials are not stored directly in this class. Instead, Jenkins credentials ID
 * is used to resolve {@link StandardUsernamePasswordCredentials}.</p>
 */
public class S3Location extends AbstractDescribableImpl<S3Location> {

    /**
     * Human-readable name of the location.
     */
    private String name;

    /**
     * S3-compatible endpoint URL.
     */
    private String endpoint;

    /**
     * S3 bucket name.
     */
    private String bucket;

    /**
     * Optional S3 region.
     */
    private String region;

    /**
     * Jenkins credentials ID.
     */
    private String credentialsId;

    /**
     * Failover priority. Lower values are processed first.
     */
    private int priority = 100;

    /**
     * Default constructor required for Jenkins data binding.
     */
    @DataBoundConstructor
    public S3Location() {
    }

    public String getName() {
        return name;
    }

    /**
     * Sets human-readable location name.
     *
     * @param name location name
     */
    @DataBoundSetter
    public void setName(String name) {
        this.name = Util.fixEmptyAndTrim(name);
    }

    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Sets S3 endpoint URL.
     *
     * @param endpoint endpoint URL
     */
    @DataBoundSetter
    public void setEndpoint(String endpoint) {
        this.endpoint = Util.fixEmptyAndTrim(endpoint);
    }

    public String getBucket() {
        return bucket;
    }

    /**
     * Sets S3 bucket name.
     *
     * @param bucket bucket name
     */
    @DataBoundSetter
    public void setBucket(String bucket) {
        this.bucket = Util.fixEmptyAndTrim(bucket);
    }

    public String getRegion() {
        return region;
    }

    /**
     * Sets optional S3 region.
     *
     * @param region region value
     */
    @DataBoundSetter
    public void setRegion(String region) {
        this.region = Util.fixEmptyAndTrim(region);
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    /**
     * Sets Jenkins credentials ID.
     *
     * @param credentialsId credentials ID
     */
    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public int getPriority() {
        return priority;
    }

    /**
     * Sets failover priority.
     *
     * @param priority priority value
     */
    @DataBoundSetter
    public void setPriority(int priority) {
        this.priority = priority;
    }

    /**
     * Returns display name used in Jenkins UI.
     *
     * @return display name
     */
    @NonNull
    public String getDisplayName() {
        if (!isEmpty(name)) return name;
        if (!isEmpty(bucket)) return bucket;
        return "Unnamed Location";
    }

    /**
     * Descriptor providing Jenkins UI integration and validation.
     */
    @Extension
    public static class DescriptorImpl extends Descriptor<S3Location> {

        private static final Pattern REGION_PATTERN = Pattern.compile("^[A-Za-z0-9-]+$");

        private final hudson.plugins.EndpointS3SCM.s3.S3Service s3Service =
                new hudson.plugins.EndpointS3SCM.s3.S3Service();
        private final S3LocationValidator validator = new S3LocationValidator();

        @NonNull
        @Override
        public String getDisplayName() {
            return "S3 Location";
        }

        /**
         * Populates credentials dropdown with compatible username/password credentials.
         *
         * @param context       Jenkins item context
         * @param credentialsId currently selected credentials ID
         * @return credentials dropdown model
         */
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item context,
                @QueryParameter String credentialsId
        ) {
            return getOptions(context, credentialsId);
        }

        /**
         * Validates region field — allows letters, numbers, and dashes.
         *
         * @param value region value
         * @return validation result
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

        /**
         * Tests S3 connection and searches latest ZIP file in configured prefix.
         *
         * @param context       Jenkins item context
         * @param endpoint      S3 endpoint URL
         * @param bucket        S3 bucket name
         * @param credentialsId Jenkins credentials ID
         * @param region        optional S3 region
         * @return validation result
         */
        @POST
        public FormValidation doTestConnection(
                @AncestorInPath Item context,
                @QueryParameter String endpoint,
                @QueryParameter String bucket,
                @QueryParameter String credentialsId,
                @QueryParameter String region
        ) {
            if (context == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return FormValidation.ok();
                }
            } else if (!context.hasPermission(Item.CONFIGURE)) {
                return FormValidation.ok();
            }

            S3Location location = new S3Location();
            location.setEndpoint(endpoint);
            location.setBucket(bucket);
            location.setCredentialsId(credentialsId);
            location.setRegion(region);

            return testConnection(context, location);
        }

        /**
         * Performs connection test and latest ZIP lookup.
         *
         * @param context  Jenkins item context
         * @param location S3 location
         * @return validation result
         */
        private FormValidation testConnection(Item context, S3Location location) {
            try {
                validator.validateForConnectionTest(location);

                StandardUsernamePasswordCredentials credentials =
                        CredentialsHelper
                                .lookupCredentialsForItem(context, location.getCredentialsId());

                if (credentials == null) {
                    return FormValidation.error("Credentials not found: " + location.getCredentialsId());
                }

                try (S3Client s3 = s3Service.createClient(
                        location.getEndpoint(),
                        location.getRegion(),
                        credentials.getUsername(),
                        Secret.toString(credentials.getPassword())
                )) {
                    s3Service.checkBucketAccess(s3, location.getBucket());
                    return FormValidation.ok("Connection OK. Bucket '" + location.getBucket() + "' is accessible.");
                }

            } catch (S3Exception e) {
                String error = e.awsErrorDetails() != null
                        ? e.awsErrorDetails().errorMessage()
                        : e.getMessage();

                return FormValidation.error("S3 error: " + error);
            } catch (SdkException e) {
                return FormValidation.error("Client error: " + e.getMessage());
            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
