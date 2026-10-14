package uk.ac.cam.cares.jps.login;

import static android.app.Activity.RESULT_CANCELED;

import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDeepLinkRequest;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.EndSessionRequest;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import javax.inject.Inject;

import kotlin.Pair;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;
import uk.ac.cam.cares.jps.loginmodule.R;

public class LoginRepository {
    private static final Logger LOGGER = LogManager.getLogger(LoginRepository.class);
    private User user;

    // Name of the Keycloak client role that marks an account as approved (see Keycloak setup)
    private static final String APPROVED_ROLE = "approved";

    LoginSource loginSource;

    @Inject
    public LoginRepository(LoginSource loginSource) {
        this.loginSource = loginSource;
    }

    public void initAuth(RepositoryCallback<Boolean> callback) {
        // already-logged-in users are also checked, so removing the role later locks them out
        loginSource.initLoginSource(withApprovalCheck(callback));
    }

    public void doAuth(RepositoryCallback<Intent> callback) {
        loginSource.doAuth(callback);
    }

    public void processAuthorizationResponse(Intent data, RepositoryCallback<Boolean> repositoryCallback) {
        // runs right after the user has logged in / signed up through Keycloak
        loginSource.processAuthorizationResponse(data, withApprovalCheck(repositoryCallback));
    }

    /**
     * Wraps a "login finished" callback: when login succeeds, additionally check that the access
     * token carries the "approved" client role. If not, the local login state is cleared (so the
     * next attempt is a fresh login that picks up a newly granted role) and the callback receives
     * an AccountException(NOT_APPROVED) instead of success.
     */
    private RepositoryCallback<Boolean> withApprovalCheck(RepositoryCallback<Boolean> downstream) {
        return new RepositoryCallback<>() {
            @Override
            public void onSuccess(Boolean result) {
                checkApproved(downstream);
            }

            @Override
            public void onFailure(Throwable error) {
                downstream.onFailure(error);
            }
        };
    }

    /**
     * Reads the access token ALREADY STORED on the phone (no network call, no refresh).
     * Do not use getAccessToken() here: at app start the AuthorizationService has not been created
     * yet, so refreshing an expired token would hit a null service and crash the app on launch.
     * The role claims in the stored token are as of the last login/refresh, which is exactly what
     * we want: a fresh login (the only way to get in after being rejected) always has current roles.
     */
    private void checkApproved(RepositoryCallback<Boolean> downstream) {
        String token = null;
        try {
            token = loginSource.authStateManager.getCurrent().getAccessToken();
        } catch (Exception e) {
            LOGGER.error("Could not read stored access token", e);
        }
        decide(token, downstream);
    }

    private void decide(String token, RepositoryCallback<Boolean> downstream) {
        LOGGER.info("TOKEN_DEBUG " + token);
        boolean approved = false;
        try {
            approved = TokenRoles.hasClientRole(token, loginSource.configuration.getClientId(), APPROVED_ROLE);
        } catch (Exception e) {
            LOGGER.error("Approval check failed", e);
        }
        if (approved) {
            downstream.onSuccess(true);
        } else {
            LOGGER.info("Logged in but account has no '" + APPROVED_ROLE + "' role");
            loginSource.authStateManager.clearLoginState();
            downstream.onFailure(new AccountException(AccountException.NOT_APPROVED));
        }
    }

    public void getAccessToken(RepositoryCallback<String> callback) {
        loginSource.getAccessToken(new RepositoryCallback<String>() {
            @Override
            public void onSuccess(String token) {
                LOGGER.info("Fetched access token: " + token);
                callback.onSuccess(token);
            }

            @Override
            public void onFailure(Throwable error) {
                LOGGER.error("Failed to fetch access token", error);
                callback.onFailure(error);
            }
        });
    }

    public void getUserInfo(RepositoryCallback<User> repositoryCallback) {
        LOGGER.info("retrieving user info");
        if (!loginSource.hasConfigurationChanged() && user != null) {
            LOGGER.info("use preloaded user info");
            repositoryCallback.onSuccess(user);
            return;
        }

        RepositoryCallback<User> callback = new RepositoryCallback<User>() {
            @Override
            public void onSuccess(User result) {
                user = result;
                repositoryCallback.onSuccess(result);
            }

            @Override
            public void onFailure(Throwable error) {
                repositoryCallback.onFailure(error);
            }
        };
        loginSource.getUserInfo(callback);
    }

    public Intent getLogOutIntent() {
        AuthorizationServiceConfiguration config = loginSource.authStateManager.getCurrent().getAuthorizationServiceConfiguration();
        if (config.endSessionEndpoint != null) {
            Intent endSessionIntent = loginSource.authService.getEndSessionRequestIntent(
                    new EndSessionRequest.Builder(config)
                            .setIdTokenHint(loginSource.authStateManager.getCurrent().getIdToken())
                            .setPostLogoutRedirectUri(loginSource.configuration.getEndSessionRedirectUri())
                            .build());
            return endSessionIntent;
        }
        return null;
    }

    public ActivityResultLauncher<Intent> getLogoutLauncher(Fragment fragment, RepositoryCallback<Pair<Boolean, String>> callback) {
        return fragment.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_CANCELED) {
                        callback.onFailure(new Throwable("cancelled"));
                    } else {
                        loginSource.authStateManager.clearLoginState();
                        String userId = user.getId();
                        user = null;
                        callback.onSuccess(new Pair<>(true, userId));
                    }
                }
        );

    }

    public MaterialAlertDialogBuilder getSessionExpiredDialog(Fragment fragment) {
        if (fragment == null || fragment.getContext() == null) {
            return null;
        }

        return new MaterialAlertDialogBuilder(fragment.getContext())
                .setTitle(R.string.session_expired_title)
                .setMessage(R.string.session_expired)
                .setPositiveButton(R.string.ok, (dialogInterface, i) -> {
                    loginSource.authStateManager.clearLoginState();
                    NavDeepLinkRequest request = NavDeepLinkRequest.Builder
                            .fromUri(Uri.parse(fragment.getString(uk.ac.cam.cares.jps.utils.R.string.onboarding_fragment_link)))
                            .build();
                    NavHostFragment.findNavController(fragment).navigate(request);
                });
    }

    public void invalidateAccessToken() {
        LOGGER.info("Invalidating cached access token after server rejection");
        loginSource.forceTokenRefresh();
    }
}