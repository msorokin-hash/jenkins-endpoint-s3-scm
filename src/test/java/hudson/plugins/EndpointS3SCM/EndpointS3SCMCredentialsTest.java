package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.AbortException;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EndpointS3SCMCredentialsTest {

    private void invokeValidateCredentials(EndpointS3SCM scm,
                                           UsernamePasswordCredentialsImpl creds) throws Exception {
        Method m = EndpointS3SCM.class
                .getDeclaredMethod("validateCredentials", StandardUsernamePasswordCredentials.class);
        m.setAccessible(true);
        try {
            m.invoke(scm, creds);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }


    @Test
    public void validateCredentials_ok() throws Exception {
        EndpointS3SCM scm = new EndpointS3SCM("http://example.com", "bucket", "key");

        UsernamePasswordCredentialsImpl creds =
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.SYSTEM,
                        "id",
                        "desc",
                        "accessKey",
                        "secretKey"
                );

        invokeValidateCredentials(scm, creds);
    }

    @Test
    public void validateCredentials_emptyUsername() {
        EndpointS3SCM scm = new EndpointS3SCM("http://example.com", "bucket", "key");

        UsernamePasswordCredentialsImpl creds =
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.SYSTEM,
                        "id",
                        "desc",
                        "",
                        "secretKey"
                );

        try {
            invokeValidateCredentials(scm, creds);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("empty username"));
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }

    @Test
    public void validateCredentials_emptyPassword() {
        EndpointS3SCM scm = new EndpointS3SCM("http://example.com", "bucket", "key");

        UsernamePasswordCredentialsImpl creds =
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.SYSTEM,
                        "id",
                        "desc",
                        "accessKey",
                        ""
                );

        try {
            invokeValidateCredentials(scm, creds);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("empty password"));
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }
}