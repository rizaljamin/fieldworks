package com.fieldworks.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;

    private final class AuthBridge {
        @JavascriptInterface
        public String currentEmail() {
            try {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                return user == null || user.getEmail() == null ? "" : user.getEmail();
            } catch (Exception error) {
                return "";
            }
        }

        @JavascriptInterface
        public void signIn(String email, String password) {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            FirebaseUser user = task.getResult().getUser();
                            sendAuthResult(true, "Welcome back.", user == null ? email : user.getEmail(), "");
                        } else {
                            sendAuthResult(false, authErrorMessage(task.getException()), email, "");
                        }
                    });
        }

        @JavascriptInterface
        public void createAccount(String name, String email, String password) {
            FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful()) {
                            sendAuthResult(false, authErrorMessage(task.getException()), email, "");
                            return;
                        }
                        FirebaseUser user = task.getResult().getUser();
                        if (user == null) {
                            sendAuthResult(false, "Account created, but the session could not be opened.", email, "");
                            return;
                        }
                        String displayName = name == null ? "" : name.trim();
                        if (displayName.isEmpty()) {
                            sendAuthResult(true, "Your FieldWorks account is ready.", user.getEmail(), "");
                            return;
                        }
                        UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                                .setDisplayName(displayName).build();
                        user.updateProfile(profile).addOnCompleteListener(profileTask ->
                                sendAuthResult(true, "Your FieldWorks account is ready.",
                                        user.getEmail(), ""));
                    });
        }

        @JavascriptInterface
        public void sendPasswordReset(String email) {
            FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim())
                    .addOnCompleteListener(task -> sendAuthResult(task.isSuccessful(),
                            task.isSuccessful()
                                    ? "Password reset email sent. Check your inbox."
                                    : authErrorMessage(task.getException()), email, "reset"));
        }

        @JavascriptInterface
        public void signOut() {
            FirebaseAuth.getInstance().signOut();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient());
        webView.setVerticalScrollBarEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        webView.addJavascriptInterface(new AuthBridge(), "FieldWorksAuth");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private String authErrorMessage(Exception error) {
        String code = error instanceof com.google.firebase.auth.FirebaseAuthException
                ? ((com.google.firebase.auth.FirebaseAuthException) error).getErrorCode() : "";
        if (code.contains("INVALID_EMAIL")) return "Please enter a valid email address.";
        if (code.contains("WEAK_PASSWORD")) return "Use a stronger password (at least 6 characters).";
        if (code.contains("EMAIL_EXISTS")) return "An account already exists for this email.";
        if (code.contains("INVALID_LOGIN_CREDENTIALS") || code.contains("INVALID_PASSWORD")
                || code.contains("EMAIL_NOT_FOUND")) return "Email or password is incorrect.";
        if (code.contains("OPERATION_NOT_ALLOWED")) return "Email sign-in is not enabled in Firebase yet.";
        if (code.contains("NETWORK_REQUEST_FAILED")) return "Connection problem. Check your internet and try again.";
        String detail = error == null ? "" : error.getLocalizedMessage();
        return "Could not sign in. " + (detail == null || detail.isEmpty() ? "Please try again." : detail);
    }

    private void sendAuthResult(boolean success, String message, String email, String action) {
        if (webView == null) return;
        try {
            JSONObject result = new JSONObject();
            result.put("success", success);
            result.put("message", message == null ? "" : message);
            result.put("email", email == null ? "" : email);
            result.put("action", action == null ? "" : action);
            runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onFieldWorksAuthResult && window.onFieldWorksAuthResult("
                            + result + ")", null));
        } catch (Exception ignored) {
            // Keep sign-in errors within the login card.
        }
    }
}
