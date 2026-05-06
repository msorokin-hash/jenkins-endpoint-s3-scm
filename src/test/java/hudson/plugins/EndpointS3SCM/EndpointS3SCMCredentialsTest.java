package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.AbortException;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EndpointS3SCMCredentialsTest {

    private final EndpointS3SCMConfigValidator validator = new EndpointS3SCMConfigValidator();

    private UsernamePasswordCredentialsImpl creds(String username, String password) {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM,
                "id",
                "desc",
                username,
                password
        );
    }

    @Test
    public void validateCredentials_ok() throws Exception {
        validator.validateCredentials(creds("accessKey", "secretKey"));
    }

    @Test
    public void validateCredentials_emptyUsername() {
        assertAbort(creds("", "secretKey"), "empty username");
    }

    @Test
    public void validateCredentials_emptyPassword() {
        assertAbort(creds("accessKey", ""), "empty password");
    }

    private void assertAbort(UsernamePasswordCredentialsImpl creds, String expectedContent) {
        try {
            validator.validateCredentials(creds);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains(expectedContent));
        }
    }
}