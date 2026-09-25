package net.kdt.pojavlaunch.authenticator.microsoft;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.Tools;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Microsoft sign-in with a device code ("go to microsoft.com/link and enter ABCD1234"), for places
 * where the web login can't be shown, like the VR menu. Uses the same live.com client as the web
 * login, so the refresh token it returns goes through {@link MicrosoftBackgroundLogin} as usual.
 */
public class MicrosoftDeviceCodeLogin {
    private static final String CLIENT_ID = "00000000402b5328";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final String CODE_URL = "https://login.live.com/oauth20_connect.srf";
    private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";

    public static class DeviceCode {
        public final String userCode;
        public final String verificationUri;
        final String deviceCode;
        final long expiresAt;
        long intervalMillis;

        DeviceCode(JSONObject json) throws JSONException {
            userCode = json.getString("user_code");
            verificationUri = json.getString("verification_uri");
            deviceCode = json.getString("device_code");
            expiresAt = System.currentTimeMillis() + json.getLong("expires_in") * 1000;
            intervalMillis = json.optLong("interval", 5) * 1000;
        }

        /** Instructions to show the user */
        public String message() {
            return "Go to " + verificationUri + " and enter the code " + userCode;
        }
    }

    public interface CancelCheck {
        boolean isCancelled();
    }

    /** Thrown when the code expired or the user declined the sign-in */
    public static class LoginFailedException extends IOException {
        public LoginFailedException(String message) { super(message); }
    }

    public static DeviceCode requestCode() throws IOException, JSONException {
        return new DeviceCode(post(CODE_URL, "client_id=" + CLIENT_ID
                + "&scope=" + URLEncoder.encode(SCOPE, "UTF-8")
                + "&response_type=device_code"));
    }

    /**
     * Blocks until the user finishes signing in, then returns the Microsoft refresh token.
     * @return the refresh token, or null if cancelled
     */
    public static String waitForRefreshToken(@NonNull DeviceCode code, @NonNull CancelCheck cancelCheck)
            throws IOException, JSONException, InterruptedException {
        String form = "client_id=" + CLIENT_ID
                + "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8")
                + "&device_code=" + URLEncoder.encode(code.deviceCode, "UTF-8");
        while (System.currentTimeMillis() < code.expiresAt) {
            Thread.sleep(code.intervalMillis);
            if (cancelCheck.isCancelled()) return null;
            JSONObject response = post(TOKEN_URL, form);
            if (response.has("refresh_token")) return response.getString("refresh_token");

            String error = response.optString("error");
            switch (error) {
                case "authorization_pending":
                    break;
                case "slow_down":
                    code.intervalMillis += 5000;
                    break;
                case "expired_token":
                    throw new LoginFailedException("The sign-in code expired, please try again");
                case "authorization_declined":
                case "access_denied":
                    throw new LoginFailedException("The sign-in was declined");
                default:
                    throw new IOException("Microsoft sign-in failed: " + response);
            }
        }
        throw new LoginFailedException("The sign-in code expired, please try again");
    }

    /** POSTs a form and returns the JSON body, for error responses too (pending polls come back as HTTP 400) */
    private static JSONObject post(String url, String form) throws IOException, JSONException {
        byte[] body = form.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            InputStream is = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) throw new IOException("Empty response from Microsoft, HTTP " + conn.getResponseCode());
            return new JSONObject(Tools.read(is));
        } finally {
            conn.disconnect();
        }
    }
}
