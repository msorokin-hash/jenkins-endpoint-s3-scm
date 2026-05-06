package hudson.plugins.EndpointS3SCM;

import hudson.AbortException;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EndpointS3SCMValidateConfigTest {

    private final EndpointS3SCMConfigValidator validator = new EndpointS3SCMConfigValidator();

    private void invokeValidateConfig(EndpointS3SCM scm) throws Exception {
        validator.validate(scm);
    }

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
    public void validateConfig_ok() throws Exception {
        EndpointS3SCM scm = new EndpointS3SCM(null);
        scm.setLocations(Collections.singletonList(
                location("http://example.com", "bucket", "projects", "creds")
        ));

        invokeValidateConfig(scm);
    }

    @Test
    public void validateConfig_emptyPrefix() {
        EndpointS3SCM scm = new EndpointS3SCM(null);
        scm.setLocations(Collections.singletonList(
                location("http://example.com", "bucket", " ", "creds")
        ));

        assertAbort(scm, "Prefix required for source #0");
    }

    @Test
    public void validateConfig_emptyLocations() {
        EndpointS3SCM scm = new EndpointS3SCM(null);

        assertAbort(scm, "At least one S3 source is required");
    }

    @Test
    public void validateConfig_emptyEndpoint() {
        EndpointS3SCM scm = new EndpointS3SCM(null);
        scm.setLocations(Collections.singletonList(
                location("   ", "bucket", "projects", "creds")
        ));

        assertAbort(scm, "Endpoint required for source #0");
    }

    @Test
    public void validateConfig_invalidEndpoint() {
        EndpointS3SCM scm = new EndpointS3SCM(null);
        scm.setLocations(Collections.singletonList(
                location("not a url", "bucket", "projects", "creds")
        ));

        assertAbort(scm, "Invalid URL for source #0");
    }

    @Test
    public void validateConfig_emptyBucket() {
        EndpointS3SCM scm = new EndpointS3SCM(null);
        scm.setLocations(Collections.singletonList(
                location("http://example.com", " ", "projects", "creds")
        ));

        assertAbort(scm, "Bucket required for source #0");
    }

    @Test
    public void validateConfig_emptyCredentialsId() {
        EndpointS3SCM scm = new EndpointS3SCM(null);
        scm.setLocations(Collections.singletonList(
                location("http://example.com", "bucket", "projects", " ")
        ));

        assertAbort(scm, "Credentials required for source #0");
    }

    private void assertAbort(EndpointS3SCM scm, String expectedContent) {
        try {
            invokeValidateConfig(scm);
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
                "Error message should contain prefix [EndpointS3SCM]",
                actual.contains("[EndpointS3SCM]")
        );

        assertTrue(
                "Error message should contain: " + expectedContent + " (actual: " + actual + ")",
                actual.contains(expectedContent)
        );
    }
}