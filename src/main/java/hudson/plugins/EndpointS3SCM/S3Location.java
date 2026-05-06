package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static hudson.plugins.EndpointS3SCM.PluginUtils.getOptions;
import static hudson.plugins.EndpointS3SCM.PluginUtils.isEmpty;

/**
 * Represents a single S3-compatible storage location.
 *
 * <p>A location defines a specific S3 endpoint, bucket, prefix and credentials
 * used during checkout.</p>
 *
 * <p>This class is used inside {@link EndpointS3SCM} and may be configured multiple times
 * to support failover and priority-based source selection.</p>
 *
 * <p>Credentials are not stored directly in this class. Instead, Jenkins credentials ID
 * is used to resolve {@link StandardUsernamePasswordCredentials}.</p>
 */
public class S3Location extends AbstractDescribableImpl<S3Location> {

    private static final long LARGE_OBJECT_WARNING_SIZE = 1024L * 1024 * 1024; // 1G

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
     * S3 prefix where ZIP archives are searched.
     */
    private String prefix;

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

    public String getPrefix() {
        return prefix;
    }

    /**
     * Sets S3 prefix where ZIP files are searched.
     *
     * @param prefix S3 prefix
     */
    @DataBoundSetter
    public void setPrefix(String prefix) {
        this.prefix = Util.fixEmptyAndTrim(prefix);
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

        private final S3Service s3Service = new S3Service();
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
         * Validates prefix field in Jenkins UI.
         *
         * @param value prefix value
         * @return validation result
         */
        public FormValidation doCheckPrefix(@QueryParameter String value) {
            return isEmpty(Util.fixEmptyAndTrim(value))
                    ? FormValidation.error("Please enter S3 directory/prefix.")
                    : FormValidation.ok();
        }

        /**
         * Tests S3 connection and searches latest ZIP file in configured prefix.
         *
         * @param context       Jenkins item context
         * @param endpoint      S3 endpoint URL
         * @param bucket        S3 bucket name
         * @param prefix        S3 prefix
         * @param credentialsId Jenkins credentials ID
         * @param region        optional S3 region
         * @return validation result
         */
        @POST
        public FormValidation doTestConnection(
                @AncestorInPath Item context,
                @QueryParameter String endpoint,
                @QueryParameter String bucket,
                @QueryParameter String prefix,
                @QueryParameter String credentialsId,
                @QueryParameter String region
        ) {
            if (context == null || !context.hasPermission(Item.CONFIGURE)) {
                return FormValidation.ok();
            }

            S3Location location = new S3Location();
            location.setEndpoint(endpoint);
            location.setBucket(bucket);
            location.setPrefix(prefix);
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
                        CredentialsHelper.lookupCredentialsForItem(context, location.getCredentialsId());

                if (credentials == null) {
                    return FormValidation.error("Credentials not found: " + location.getCredentialsId());
                }

                try (S3Client s3 = s3Service.createClient(
                        location.getEndpoint(),
                        location.getRegion(),
                        credentials.getUsername(),
                        Secret.toString(credentials.getPassword())
                )) {
                    S3ObjectCandidate latest = s3Service.findLatestZipObject(
                            s3,
                            location.getBucket(),
                            location.getPrefix()
                    );

                    if (latest == null) {
                        return FormValidation.warning(
                                "Connection OK, but no ZIP files found in prefix: " + location.getPrefix()
                        );
                    }

                    String message = String.format(
                            "Connection OK. Latest ZIP: %s (%d bytes, %s)",
                            latest.getKey(),
                            latest.getSize(),
                            latest.getLastModified()
                    );

                    if (latest.getSize() > LARGE_OBJECT_WARNING_SIZE) {
                        message += " (large object)";
                    }

                    return FormValidation.ok(message);
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