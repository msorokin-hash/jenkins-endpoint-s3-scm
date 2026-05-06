package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.AbortException;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EndpointS3SCMConfigValidatorTest {

    private final EndpointS3SCMConfigValidator validator = new EndpointS3SCMConfigValidator();

    private S3Location location(String endpoint,
                                String bucket,
                                String prefix,
                                String credentialsId) {
        S3Location location = new S3Location();
        location.setName("primary");
        location.setEndpoint(endpoint);
        location.setBucket(bucket);
        location.setPrefix(prefix);
        location.setCredentialsId(credentialsId);
        location.setPriority(10);
        return location;
    }

    private EndpointS3SCM scmWithLocation(S3Location location) {
        EndpointS3SCM scm = new EndpointS3SCM(null);
        scm.setLocations(Collections.singletonList(location));
        return scm;
    }

    @Test
    public void validate_ok() throws Exception {
        EndpointS3SCM scm = scmWithLocation(
                location("http://example.com", "bucket", "releases/", "creds-id")
        );

        validator.validate(scm);
    }

    @Test
    public void validate_usesLegacyPrefixWhenLocationPrefixIsEmpty() throws Exception {
        EndpointS3SCM scm = new EndpointS3SCM("legacy-prefix/");
        scm.setLocations(Collections.singletonList(
                location("http://example.com", "bucket", " ", "creds-id")
        ));

        validator.validate(scm);
    }

    @Test
    public void validate_emptyLocations() {
        EndpointS3SCM scm = new EndpointS3SCM(null);

        assertAbort(scm, "At least one S3 source is required");
    }

    @Test
    public void validate_emptyEndpoint() {
        EndpointS3SCM scm = scmWithLocation(
                location(" ", "bucket", "releases/", "creds-id")
        );

        assertAbort(scm, "Endpoint required for source #0");
    }

    @Test
    public void validate_invalidEndpoint() {
        EndpointS3SCM scm = scmWithLocation(
                location("not a url", "bucket", "releases/", "creds-id")
        );

        assertAbort(scm, "Invalid URL for source #0");
    }

    @Test
    public void validate_emptyBucket() {
        EndpointS3SCM scm = scmWithLocation(
                location("http://example.com", " ", "releases/", "creds-id")
        );

        assertAbort(scm, "Bucket required for source #0");
    }

    @Test
    public void validate_emptyPrefixAndNoLegacyPrefix() {
        EndpointS3SCM scm = scmWithLocation(
                location("http://example.com", "bucket", " ", "creds-id")
        );

        assertAbort(scm, "Prefix required for source #0");
    }

    @Test
    public void validate_emptyCredentialsId() {
        EndpointS3SCM scm = scmWithLocation(
                location("http://example.com", "bucket", "releases/", " ")
        );

        assertAbort(scm, "Credentials required for source #0");
    }

    @Test
    public void validateCredentials_ok() throws Exception {
        validator.validateCredentials(credentials("accessKey", "secretKey"));
    }

    @Test
    public void validateCredentials_emptyUsername() {
        assertCredentialsAbort(credentials(" ", "secretKey"), "empty username");
    }

    @Test
    public void validateCredentials_emptyPassword() {
        assertCredentialsAbort(credentials("accessKey", " "), "empty password");
    }

    private UsernamePasswordCredentialsImpl credentials(String username, String password) {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM,
                "creds-id",
                "test credentials",
                username,
                password
        );
    }

    private void assertAbort(EndpointS3SCM scm, String expectedContent) {
        try {
            validator.validate(scm);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertMessage(e, expectedContent);
        }
    }

    private void assertCredentialsAbort(UsernamePasswordCredentialsImpl credentials,
                                        String expectedContent) {
        try {
            validator.validateCredentials(credentials);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertMessage(e, expectedContent);
        }
    }

    private void assertMessage(AbortException e, String expectedContent) {
        String actual = e.getMessage();

        assertTrue(
                "Error message should contain [EndpointS3SCM], actual: " + actual,
                actual.contains("[EndpointS3SCM]")
        );

        assertTrue(
                "Error message should contain: " + expectedContent + ", actual: " + actual,
                actual.contains(expectedContent)
        );
    }
}