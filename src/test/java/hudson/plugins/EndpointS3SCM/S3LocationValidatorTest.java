package hudson.plugins.EndpointS3SCM;

import hudson.AbortException;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class S3LocationValidatorTest {

    private final S3LocationValidator validator = new S3LocationValidator();

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
        return location;
    }

    @Test
    public void validateForConnectionTest_ok() throws Exception {
        validator.validateForConnectionTest(
                location("http://example.com", "bucket", "releases/", "creds-id")
        );
    }

    @Test
    public void validateForConnectionTest_nullLocation() {
        assertAbort(null, "Location is empty");
    }

    @Test
    public void validateForConnectionTest_emptyEndpoint() {
        assertAbort(
                location(" ", "bucket", "releases/", "creds-id"),
                "Endpoint is required"
        );
    }

    @Test
    public void validateForConnectionTest_invalidEndpoint() {
        assertAbort(
                location("not a url", "bucket", "releases/", "creds-id"),
                "Invalid endpoint URL"
        );
    }

    @Test
    public void validateForConnectionTest_emptyBucket() {
        assertAbort(
                location("http://example.com", " ", "releases/", "creds-id"),
                "Bucket is required"
        );
    }

    @Test
    public void validateForConnectionTest_emptyPrefix() {
        assertAbort(
                location("http://example.com", "bucket", " ", "creds-id"),
                "Prefix is required"
        );
    }

    @Test
    public void validateForConnectionTest_emptyCredentialsId() {
        assertAbort(
                location("http://example.com", "bucket", "releases/", " "),
                "Credentials are required"
        );
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