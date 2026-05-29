package hudson.plugins.EndpointS3SCM.config;

import hudson.AbortException;
import hudson.plugins.EndpointS3SCM.support.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@DisplayName("EndpointS3SCMConfigValidator — full validation")
public class EndpointS3SCMValidateConfigTest extends BaseTest {

    private final EndpointS3SCMConfigValidator validator = new EndpointS3SCMConfigValidator();

    private void invoke(String prefix, List<S3Location> locations) throws Exception {
        validator.validate(prefix, locations);
    }

    private S3Location location(String endpoint, String bucket, String credentialsId) {
        S3Location loc = new S3Location();
        loc.setName("primary");
        loc.setEndpoint(endpoint);
        loc.setBucket(bucket);
        loc.setCredentialsId(credentialsId);
        return loc;
    }

    @Test
    @DisplayName("validate: valid prefix and locations passes without exception")
    public void validateConfig_ok() throws Exception {
        invoke("projects", Collections.singletonList(
                location("http://example.com", "bucket", "creds")
        ));
    }

    @Test
    @DisplayName("validate: null prefix — throws AbortException with 'Directory/prefix is required'")
    public void validateConfig_emptyPrefix() {
        assertAbort(null, Collections.singletonList(
                location("http://example.com", "bucket", "creds")
        ), "Directory/prefix is required");
    }

    @Test
    @DisplayName("validate: whitespace-only prefix — throws AbortException with 'Directory/prefix is required'")
    public void validateConfig_blankPrefix() {
        assertAbort("   ", Collections.singletonList(
                location("http://example.com", "bucket", "creds")
        ), "Directory/prefix is required");
    }

    @Test
    @DisplayName("validate: empty locations list — throws AbortException with 'At least one S3 source is required'")
    public void validateConfig_emptyLocations() {
        assertAbort("projects", Collections.emptyList(), "At least one S3 source is required");
    }

    @Test
    @DisplayName("validate: location with blank endpoint — throws AbortException")
    public void validateConfig_emptyEndpoint() {
        assertAbort("projects", Collections.singletonList(
                location("   ", "bucket", "creds")
        ), "Endpoint required for source #0");
    }

    @Test
    @DisplayName("validate: location with malformed URL — throws AbortException")
    public void validateConfig_invalidEndpoint() {
        assertAbort("projects", Collections.singletonList(
                location("not a url", "bucket", "creds")
        ), "Invalid URL for source #0");
    }

    @Test
    @DisplayName("validate: location with blank bucket — throws AbortException")
    public void validateConfig_emptyBucket() {
        assertAbort("projects", Collections.singletonList(
                location("http://example.com", " ", "creds")
        ), "Bucket required for source #0");
    }

    @Test
    @DisplayName("validate: location with blank credentialsId — throws AbortException")
    public void validateConfig_emptyCredentialsId() {
        assertAbort("projects", Collections.singletonList(
                location("http://example.com", "bucket", " ")
        ), "Credentials required for source #0");
    }

    private void assertAbort(String prefix, List<S3Location> locations, String expectedContent) {
        try {
            invoke(prefix, locations);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertErrorMessage(e, expectedContent);
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }

    private void assertErrorMessage(AbortException e, String expectedContent) {
        String actual = e.getMessage();
        assertTrue(
                "Error message should contain [EndpointS3SCM]",
                actual.contains("[EndpointS3SCM]")
        );
        assertTrue(
                "Error message should contain: " + expectedContent + " (actual: " + actual + ")",
                actual.contains(expectedContent)
        );
    }
}
