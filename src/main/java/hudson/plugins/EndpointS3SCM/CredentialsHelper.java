package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.model.Item;
import hudson.model.Run;

import java.util.Collections;
import java.util.List;

import static hudson.plugins.EndpointS3SCM.PluginUtils.isEmpty;

public final class CredentialsHelper {

    /**
     * Private constructor to prevent instantiation - this is a utility class.
     */
    private CredentialsHelper() {
    }

    /**
     * Retrieves Jenkins credentials for a specific build run.
     * This method is typically used during build execution to access
     * credentials configured for the job.
     *
     * @param run           The Jenkins build run context
     * @param credentialsId The unique ID of the credentials to look up
     * @return The StandardUsernamePasswordCredentials object if found,
     * null if credentialsId is empty or credentials not found
     */
    public static StandardUsernamePasswordCredentials lookupCredentialsForRun(
            Run<?, ?> run,
            String credentialsId
    ) {
        if (run == null || isEmpty(credentialsId)) {
            return null;
        }
        Item item = run.getParent();
        return lookupCredentialsForItem(item, credentialsId);
    }

    /**
     * Retrieves Jenkins credentials for a specific Jenkins item (job/folder).
     * Searches for credentials within the item's scope and returns the
     * credentials matching the provided ID.
     *
     * @param item          The Jenkins item (job, folder, etc.) to search for credentials
     * @param credentialsId The unique ID of the credentials to look up
     * @return The StandardUsernamePasswordCredentials object if found,
     * null if credentialsId is empty or credentials not found
     */
    public static StandardUsernamePasswordCredentials lookupCredentialsForItem(
            Item item,
            String credentialsId
    ) {
        if (isEmpty(credentialsId)) return null;

        List<DomainRequirement> req = Collections.emptyList();

        // Look up all StandardUsernamePasswordCredentials available to the item
        List<StandardUsernamePasswordCredentials> all =
                CredentialsProvider.lookupCredentialsInItem(
                        StandardUsernamePasswordCredentials.class,
                        item,
                        null,               // No authentication context required
                        req                 // No domain requirements
                );

        // Find the specific credentials by ID
        return CredentialsMatchers.firstOrNull(
                all,
                CredentialsMatchers.withId(credentialsId)
        );
    }
}