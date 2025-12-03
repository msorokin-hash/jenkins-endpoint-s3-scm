package hudson.plugins.EndpointS3SCM;

import static java.net.URI.create;

public final class PluginUtils {

    /**
     * Private constructor to prevent instantiation - this is a utility class.
     */
    private PluginUtils() {
    }

    /**
     * Checks if a string is null, empty, or contains only whitespace characters.
     *
     * @param s The string to check
     * @return true if the string is null, empty, or whitespace-only; false otherwise
     */
    public static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Masks sensitive authentication information (username:password) in a URL string.
     * This is useful for logging URLs without exposing credentials.
     * Example: "https://user:pass@example.com" becomes "https://****@example.com"
     *
     * @param url The URL string that may contain user authentication information
     * @return The URL with authentication credentials masked as "****",
     * or the original URL if no credentials were found or parsing failed
     */
    public static String maskUrl(String url) {
        if (url == null) return "";
        try {
            var uri = create(url);
            if (uri.getUserInfo() != null) {
                return url.replace(uri.getUserInfo() + "@", "****@");
            }
        } catch (Exception ignored) {
            // If URL parsing fails, return the original URL unchanged
            // This ensures we don't break logging even with malformed URLs
        }
        return url;
    }
}