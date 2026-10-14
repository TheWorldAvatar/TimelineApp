package uk.ac.cam.cares.jps.login;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Reads Keycloak client roles out of an access token (a JWT).
 *
 * IMPORTANT: this only DECODES the token, it does not verify the signature. That is fine for
 * deciding which screen to show, but it is not security: anyone can edit an app. Real enforcement
 * must happen on the servers that receive the token (see the backend notes).
 */
public final class TokenRoles {

    private TokenRoles() {
    }

    /**
     * @param accessToken the raw access token string ("xxxxx.yyyyy.zzzzz")
     * @param clientId    the Keycloak client id the role belongs to (e.g. "twa-timeline-app")
     * @param role        the client role to look for (e.g. "approved")
     * @return true only if resource_access.<clientId>.roles contains the role; any problem
     *         (null token, malformed token, missing claim) returns false, i.e. "not approved"
     */
    public static boolean hasClientRole(String accessToken, String clientId, String role) {
        if (accessToken == null || clientId == null || role == null) {
            return false;
        }
        try {
            // A JWT is three base64url parts separated by dots; the middle one is the payload.
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return false;
            }
            byte[] payload = Base64.decode(parts[1],
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));

            JSONObject resourceAccess = json.optJSONObject("resource_access");
            if (resourceAccess == null) {
                return false;
            }
            JSONObject client = resourceAccess.optJSONObject(clientId);
            if (client == null) {
                return false;
            }
            JSONArray roles = client.optJSONArray("roles");
            if (roles == null) {
                return false;
            }
            for (int i = 0; i < roles.length(); i++) {
                if (role.equals(roles.optString(i))) {
                    return true;
                }
            }
        } catch (Exception e) {
            // malformed token: treat as not approved
        }
        return false;
    }
}
