package com.shodanrecon.mobile;

import org.json.JSONObject;

import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import okhttp3.HttpUrl;

/** Resolves requests only inside an explicitly registered camera's HTTP origin. */
public final class CameraUrls {
    private CameraUrls() {}

    public static String url(JSONObject camera, String pathOrUrl, int channel) {
        if (camera == null || !camera.optBoolean("authorized", false))
            throw new IllegalArgumentException("Confirm that this camera is yours or that you are authorized to administer it.");
        String scheme = camera.optString("scheme", "http").toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw new IllegalArgumentException("Camera scheme must be HTTP or HTTPS.");
        String host = validHost(camera.optString("host", ""));
        int defaultPort = scheme.equals("https") ? 443 : 80;
        Object portValue = camera.opt("port");
        int port;
        try {
            String value = portValue == null || portValue == JSONObject.NULL || portValue.toString().isEmpty()
                    ? Integer.toString(defaultPort) : portValue.toString();
            if (!value.matches("[0-9]{1,5}")) throw new NumberFormatException();
            port = Integer.parseInt(value);
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("Camera port must be between 1 and 65535."); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Camera port must be between 1 and 65535.");
        if (pathOrUrl != null) rejectControlCharacters(pathOrUrl);
        String configured = pathOrUrl == null || pathOrUrl.isEmpty() ? "/" : pathOrUrl;
        if (configured.contains("{channel}")) {
            if (channel < 1 || channel > 99999) throw new IllegalArgumentException("Choose a valid snapshot channel.");
            configured = configured.replace("{channel}", Integer.toString(channel));
        }
        rejectControlCharacters(configured);
        if (configured.contains("\\") || configured.startsWith("//"))
            throw new IllegalArgumentException("Use a camera path starting with a single slash.");
        if (configured.contains("{" ) || configured.contains("}"))
            throw new IllegalArgumentException("Only the {channel} URL template is supported.");
        try {
            URI parsed = new URI(configured);
            if (parsed.getRawFragment() != null || parsed.getRawUserInfo() != null)
                throw new IllegalArgumentException("Camera URLs cannot contain embedded credentials or fragments.");
            if (parsed.getScheme() != null && !"http".equalsIgnoreCase(parsed.getScheme()) && !"https".equalsIgnoreCase(parsed.getScheme()))
                throw new IllegalArgumentException("Use an HTTP or HTTPS camera path.");
            HttpUrl origin = new HttpUrl.Builder().scheme(scheme).host(host).port(port).build();
            HttpUrl resolved = parsed.getScheme() == null
                    ? origin.resolve(configured.startsWith("/") ? configured : "/" + configured)
                    : HttpUrl.parse(configured);
            if (resolved == null) throw new IllegalArgumentException("Camera URL is invalid. Encode spaces in the path.");
            if (!resolved.username().isEmpty() || !resolved.password().isEmpty())
                throw new IllegalArgumentException("Save credentials in the credential manager, not in camera URLs.");
            if (!sameOrigin(origin, resolved))
                throw new IllegalArgumentException("The URL must use this camera's configured scheme, host and port.");
            for (String key : resolved.queryParameterNames()) {
                String compact = key.toLowerCase(Locale.US).replace("-", "").replace("_", "");
                if (compact.matches(".*(password|passwd|credential|authorization|apikey|accesstoken|authtoken).*" )
                        || compact.equals("pass") || compact.equals("pwd") || compact.equals("user")
                        || compact.equals("username") || compact.equals("token") || compact.equals("key")
                        || compact.equals("auth") || compact.equals("secret"))
                    throw new IllegalArgumentException("Store usernames and passwords in the credential manager, not URL parameters.");
            }
            rejectCredentialInUrl(resolved, camera.optString("username", ""), camera.optString("password", ""));
            return resolved.toString();
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("Camera URL is invalid. Encode spaces and remove embedded credentials."); }
    }

    static boolean sameOrigin(HttpUrl first, HttpUrl second) {
        return first.scheme().equals(second.scheme()) && first.host().equals(second.host()) && first.port() == second.port();
    }

    static void rejectCredentialInUrl(HttpUrl url, String username, String password) {
        String text = url.encodedPath() + (url.encodedQuery() == null ? "" : "?" + url.encodedQuery());
        String decoded;
        try { decoded = java.net.URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8.name()); }
        catch (Exception ex) { throw new IllegalArgumentException("Camera path encoding is invalid."); }
        // Passwords must remain in encrypted storage. A username as a complete path/query value is also refused.
        if (password != null && !password.isEmpty() && (text.contains(password) || decoded.contains(password)))
            throw new IllegalArgumentException("Remove the saved password from the camera path and use the credential manager.");
        if (username != null && !username.isEmpty()) {
            for (String value : decoded.split("[/=&?]")) if (value.equals(username))
                throw new IllegalArgumentException("Remove the saved username from the camera path and use the credential manager.");
        }
    }

    private static String validHost(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim()))
            throw new IllegalArgumentException("Enter an IP address or camera hostname without a URL or port.");
        rejectControlCharacters(value);
        String host = value;
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.contains(":")) {
            if (!host.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Camera IPv6 address is invalid.");
            try { return new HttpUrl.Builder().scheme("http").host(host).build().host(); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Camera IPv6 address is invalid."); }
        }
        if (host.matches("[0-9.]+")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Camera IPv4 address is invalid.");
            StringBuilder clean = new StringBuilder();
            for (String part : parts) {
                if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
                    throw new IllegalArgumentException("Camera IPv4 address is invalid.");
                if (clean.length() > 0) clean.append('.');
                clean.append(part);
            }
            return clean.toString();
        }
        try { host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.US); }
        catch (Exception ex) { throw new IllegalArgumentException("Camera hostname is invalid."); }
        if (host.length() > 253) throw new IllegalArgumentException("Camera hostname is too long.");
        for (String label : host.split("\\.", -1))
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
                throw new IllegalArgumentException("Camera hostname is invalid.");
        return host;
    }

    private static void rejectControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) <= 32 || Character.isISOControl(value.charAt(i)))
            throw new IllegalArgumentException("Camera URLs cannot contain whitespace or control characters.");
        if (value.matches("(?is).*%(?:0[0-9a-f]|1[0-9a-f]|7f).*"))
            throw new IllegalArgumentException("Camera URLs cannot contain encoded control characters.");
    }
}
