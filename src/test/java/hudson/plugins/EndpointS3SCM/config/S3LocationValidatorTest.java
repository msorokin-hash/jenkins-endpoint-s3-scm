package hudson.plugins.EndpointS3SCM.config;

import hudson.AbortException;
import hudson.plugins.EndpointS3SCM.support.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@DisplayName("S3LocationValidator")
public class S3LocationValidatorTest extends BaseTest {

    private final S3LocationValidator validator = new S3LocationValidator();

    private S3Location location(String endpoint, String bucket, String credentialsId) {
        S3Location loc = new S3Location();
        loc.setName("primary");
        loc.setEndpoint(endpoint);
        loc.setBucket(bucket);
        loc.setCredentialsId(credentialsId);
        return loc;
    }

    @Test
    @DisplayName("validateForConnectionTest: valid location passes without exception")
    public void validateForConnectionTest_ok() throws Exception {
        validator.validateForConnectionTest(
                location("http://example.com", "bucket", "creds-id")
        );
    }

    @Test
    @DisplayName("validateForConnectionTest: null location throws AbortException with 'Location is empty'")
    public void validateForConnectionTest_nullLocation() {
        assertAbort(null, "Location is empty");
    }

    @Test
    @DisplayName("validateForConnectionTest: blank endpoint throws AbortException with 'Endpoint is required'")
    public void validateForConnectionTest_emptyEndpoint() {
        assertAbort(location(" ", "bucket", "creds-id"), "Endpoint is required");
    }

    @Test
    @DisplayName("validateForConnectionTest: non-URL endpoint throws AbortException with 'Invalid endpoint URL'")
    public void validateForConnectionTest_invalidEndpoint() {
        assertAbort(location("not a url", "bucket", "creds-id"), "Invalid endpoint URL");
    }

    @Test
    @DisplayName("validateForConnectionTest: blank bucket throws AbortException with 'Bucket is required'")
    public void validateForConnectionTest_emptyBucket() {
        assertAbort(location("http://example.com", " ", "creds-id"), "Bucket is required");
    }

    @Test
    @DisplayName("validateForConnectionTest: blank credentialsId throws AbortException with 'Credentials are required'")
    public void validateForConnectionTest_emptyCredentialsId() {
        assertAbort(location("http://example.com", "bucket", " "), "Credentials are required");
    }

    private void assertAbort(S3Location location, String expectedContent) {
        try {
            validator.validateForConnectionTest(location);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(
                    "Error message should contain: " + expectedContent + ", actual: " + e.getMessage(),
                    e.getMessage().contains(expectedContent)
            );
        }
    }
}
