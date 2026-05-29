package hudson.plugins.EndpointS3SCM.config;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.AbortException;
import hudson.plugins.EndpointS3SCM.support.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@DisplayName("EndpointS3SCMConfigValidator")
public class EndpointS3SCMConfigValidatorTest extends BaseTest {

    private final EndpointS3SCMConfigValidator validator = new EndpointS3SCMConfigValidator();

    private S3Location location(String endpoint, String bucket, String credentialsId) {
        S3Location loc = new S3Location();
        loc.setName("primary");
        loc.setEndpoint(endpoint);
        loc.setBucket(bucket);
        loc.setCredentialsId(credentialsId);
        loc.setPriority(10);
        return loc;
    }

    @Test
    @DisplayName("validate: valid prefix and location passes without exception")
    public void validate_ok() throws Exception {
        validator.validate("releases/", Collections.singletonList(
                location("http://example.com", "bucket", "creds-id")
        ));
    }

    @Test
    @DisplayName("validate: null prefix throws AbortException with 'Directory/prefix is required'")
    public void validate_emptyPrefix() {
        assertAbort(null, Collections.singletonList(
                location("http://example.com", "bucket", "creds-id")
        ), "Directory/prefix is required");
    }

    @Test
    @DisplayName("validate: empty locations list throws AbortException with 'At least one S3 source is required'")
    public void validate_emptyLocations() {
        assertAbort("releases/", Collections.emptyList(), "At least one S3 source is required");
    }

    @Test
    @DisplayName("validate: location with blank endpoint throws AbortException")
    public void validate_emptyEndpoint() {
        assertAbort("releases/", Collections.singletonList(
                location(" ", "bucket", "creds-id")
        ), "Endpoint required for source #0");
    }

    @Test
    @DisplayName("validate: location with non-URL endpoint throws AbortException")
    public void validate_invalidEndpoint() {
        assertAbort("releases/", Collections.singletonList(
                location("not a url", "bucket", "creds-id")
        ), "Invalid URL for source #0");
    }

    @Test
    @DisplayName("validate: location with blank bucket throws AbortException")
    public void validate_emptyBucket() {
        assertAbort("releases/", Collections.singletonList(
                location("http://example.com", " ", "creds-id")
        ), "Bucket required for source #0");
    }

    @Test
    @DisplayName("validate: location with blank credentialsId throws AbortException")
    public void validate_emptyCredentialsId() {
        assertAbort("releases/", Collections.singletonList(
                location("http://example.com", "bucket", " ")
        ), "Credentials required for source #0");
    }

    @Test
    @DisplayName("validateCredentials: valid username and password passes without exception")
    public void validateCredentials_ok() throws Exception {
        validator.validateCredentials(credentials("accessKey", "secretKey"));
    }

    @Test
    @DisplayName("validateCredentials: blank username throws AbortException")
    public void validateCredentials_emptyUsername() {
        assertCredentialsAbort(credentials(" ", "secretKey"), "empty username");
    }

    @Test
    @DisplayName("validateCredentials: blank password throws AbortException")
    public void validateCredentials_emptyPassword() {
        assertCredentialsAbort(credentials("accessKey", " "), "empty password");
    }

    private UsernamePasswordCredentialsImpl credentials(String username, String password) {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM, "creds-id", "test credentials", username, password
        );
    }

    private void assertAbort(String prefix, List<S3Location> locations, String expectedContent) {
        try {
            validator.validate(prefix, locations);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertMessage(e, expectedContent);
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }

    private void assertCredentialsAbort(UsernamePasswordCredentialsImpl credentials, String expectedContent) {
        try {
            validator.validateCredentials(credentials);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertMessage(e, expectedContent);
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }

    private void assertMessage(AbortException e, String expectedContent) {
        String actual = e.getMessage();
        assertTrue("Error message should contain [EndpointS3SCM], actual: " + actual,
                actual.contains("[EndpointS3SCM]"));
        assertTrue("Error message should contain: " + expectedContent + ", actual: " + actual,
                actual.contains(expectedContent));
    }
}
