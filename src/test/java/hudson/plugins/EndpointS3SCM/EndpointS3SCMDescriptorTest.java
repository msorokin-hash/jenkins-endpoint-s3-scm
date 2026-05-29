package hudson.plugins.EndpointS3SCM;

import hudson.util.FormValidation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@DisplayName("EndpointS3SCMDescriptor — display name, applicability and prefix validation")
class EndpointS3SCMDescriptorTest {

    private final EndpointS3SCMDescriptor descriptor = new EndpointS3SCMDescriptor();

    @Test
    @DisplayName("getDisplayName: returns 'S3'")
    void getDisplayName_returnsS3() {
        assertEquals("S3", descriptor.getDisplayName());
    }

    @Test
    @DisplayName("isApplicable: returns true when project is null (parameter is unused)")
    void isApplicable_nullProject_returnsTrue() {
        assertTrue(descriptor.isApplicable(null));
    }

    @Test
    @DisplayName("doCheckPrefix: null — returns ERROR")
    void doCheckPrefix_null_returnsError() {
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckPrefix(null).kind);
    }

    @Test
    @DisplayName("doCheckPrefix: empty string — returns ERROR")
    void doCheckPrefix_empty_returnsError() {
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckPrefix("").kind);
    }

    @Test
    @DisplayName("doCheckPrefix: whitespace only — returns ERROR")
    void doCheckPrefix_whitespaceOnly_returnsError() {
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckPrefix("   ").kind);
    }

    @Test
    @DisplayName("doCheckPrefix: error message contains descriptive text")
    void doCheckPrefix_empty_errorMessageIsDescriptive() {
        FormValidation result = descriptor.doCheckPrefix("");
        assertTrue(result.getMessage().contains("S3 directory/prefix"));
    }

    @Test
    @DisplayName("doCheckPrefix: valid prefix — returns OK")
    void doCheckPrefix_validPrefix_returnsOk() {
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckPrefix("releases/").kind);
    }

    @Test
    @DisplayName("doCheckPrefix: prefix with surrounding spaces — trimmed then accepted as OK")
    void doCheckPrefix_paddedValue_trimmedAndAccepted() {
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckPrefix("  releases/  ").kind);
    }

    @Test
    @DisplayName("doCheckPrefix: single character — returns OK")
    void doCheckPrefix_singleChar_returnsOk() {
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckPrefix("x").kind);
    }
}
