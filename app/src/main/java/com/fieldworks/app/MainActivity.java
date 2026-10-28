package com.fieldworks.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.firebase.auth.FirebaseAuth;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.OAuthProvider;
import android.os.CancellationSignal;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private BillingClient billingClient;
    private final ExecutorService subscriptionExecutor = Executors.newSingleThreadExecutor();
    private static final String SUBSCRIPTION_FUNCTION_URL = "https://asia-southeast1-fieldworks-512712.cloudfunctions.net/fieldWorksSubscription";
    private volatile boolean restoreRequested;
    private final Map<String, ProductDetails> subscriptionProducts = new HashMap<>();
    private static final String PLAY_TESTING_NOTICE = "Google Play subscriptions require installing FieldWorks from a Google Play testing track. Purchases and restore may fail when this app is sideloaded.";
    private static final String[] SUBSCRIPTION_PRODUCT_IDS = { "fw2026", "fw2026_stu" };

    private String resourceString(String name) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id == 0 ? "" : getString(id);
    }

    private void handleGoogleCredential(GetCredentialResponse response) {
        Credential credential = response.getCredential();
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    .equals(credential.getType())) {
            sendAuthResult(false, "Google returned an unsupported sign-in credential.", "");
            return;
        }
        try {
            GoogleIdTokenCredential googleCredential =
                    GoogleIdTokenCredential.createFrom(credential.getData());
            AuthCredential firebaseCredential = GoogleAuthProvider.getCredential(
                    googleCredential.getIdToken(), null);
            FirebaseAuth.getInstance().signInWithCredential(firebaseCredential)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            FirebaseUser user = task.getResult().getUser();
                            sendAuthResult(true, "Signed in with Google.",
                                    user == null ? googleCredential.getId() : user.getEmail());
                        } else {
                            sendAuthResult(false, authErrorMessage(task.getException()), "");
                        }
                    });
        } catch (Exception error) {
            sendAuthResult(false, "Google returned an invalid sign-in response: "
                    + (error.getLocalizedMessage() == null ? "Please try again." : error.getLocalizedMessage()), "");
        }
    }

    private void launchGoogleButtonFlow(CredentialManager manager, String clientId) {
        GetSignInWithGoogleOption googleOption =
                new GetSignInWithGoogleOption.Builder(clientId).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleOption)
                .build();
        manager.getCredentialAsync(this, request, new CancellationSignal(), getMainExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        handleGoogleCredential(response);
                    }

                    @Override
                    public void onError(GetCredentialException error) {
                        String message = error.getLocalizedMessage();
                        if (message == null || message.trim().isEmpty()) {
                            message = "Google account selection was cancelled or could not start.";
                        }
                        sendAuthResult(false, message, "");
                    }
                });
    }

    private void launchGoogleSignIn() {
        String clientId = resourceString("default_web_client_id");
        if (clientId.isEmpty()) {
            sendAuthResult(false, "Google setup is incomplete. Download the refreshed Firebase config and rebuild.", "");
            return;
        }
        GetGoogleIdOption googleOption = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(false)
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleOption)
                .build();
        CredentialManager manager = CredentialManager.create(this);
        manager.getCredentialAsync(this, request, new CancellationSignal(), getMainExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        handleGoogleCredential(response);
                    }

                    @Override
                    public void onError(GetCredentialException error) {
                        if (error instanceof NoCredentialException) {
                            launchGoogleButtonFlow(manager, clientId);
                            return;
                        }
                        String message = error.getLocalizedMessage();
                        if (message == null || message.trim().isEmpty()) {
                            message = "Google sign-in was cancelled or could not start.";
                        }
                        sendAuthResult(false, message, "");
                    }
                });
    }
    private final class BillingBridge {
        @JavascriptInterface
        public void loadProducts() {
            runOnUiThread(() -> connectBilling());
        }

        @JavascriptInterface
        public void purchase(String productId) {
            runOnUiThread(() -> launchSubscriptionPurchase(productId));
        }

        @JavascriptInterface
        public void restorePurchases() {
            runOnUiThread(() -> {
                restoreRequested = true;
                connectBilling();
            });
        }

        @JavascriptInterface
        public void syncEntitlement() {
            runOnUiThread(() -> syncSubscriptionEntitlement());
        }
    }

    private void connectBilling() {
        if (billingClient == null) {
            billingClient = BillingClient.newBuilder(this)
                    .setListener((billingResult, purchases) -> {
                        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
                                && purchases != null) {
                            for (Purchase purchase : purchases) processSubscriptionPurchase(purchase);
                        } else if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.USER_CANCELED) {
                            sendBillingMessage(false, PLAY_TESTING_NOTICE);
                        }
                    })
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                            .enableOneTimeProducts().build())
                    .enableAutoServiceReconnection()
                    .build();
        }
        if (billingClient.isReady()) {
            querySubscriptionProducts();
            queryOwnedSubscriptions();
            return;
        }
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    querySubscriptionProducts();
                    queryOwnedSubscriptions();
                } else {
                    sendBillingMessage(false, PLAY_TESTING_NOTICE);
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                // BillingClient automatically reconnects on the next request.
            }
        });
    }

    private void querySubscriptionProducts() {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (String productId : SUBSCRIPTION_PRODUCT_IDS) {
            products.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build());
        }
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(products).build();
        billingClient.queryProductDetailsAsync(params, (result, response) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                sendBillingCallback("onFieldWorksBillingProducts", new JSONObject());
                return;
            }
            subscriptionProducts.clear();
            JSONObject catalog = new JSONObject();
            for (ProductDetails details : response.getProductDetailsList()) {
                subscriptionProducts.put(details.getProductId(), details);
                String formattedPrice = "";
                List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
                if (offers != null && !offers.isEmpty()) {
                    List<ProductDetails.PricingPhase> phases =
                            offers.get(0).getPricingPhases().getPricingPhaseList();
                    if (!phases.isEmpty()) formattedPrice = phases.get(phases.size() - 1).getFormattedPrice();
                }
                try { catalog.put(details.getProductId(), formattedPrice); } catch (Exception ignored) {}
            }
            sendBillingCallback("onFieldWorksBillingProducts", catalog);
        });
    }

    private void launchSubscriptionPurchase(String productId) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            sendBillingMessage(false, "Sign in to a FieldWorks account before subscribing.");
            return;
        }
        ProductDetails details = subscriptionProducts.get(productId);
        if (details == null) {
            connectBilling();
            sendBillingMessage(false, PLAY_TESTING_NOTICE);
            return;
        }
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if (offers == null || offers.isEmpty()) {
            sendBillingMessage(false, PLAY_TESTING_NOTICE);
            return;
        }
        BillingFlowParams.ProductDetailsParams productParams =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offers.get(0).getOfferToken())
                        .build();
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(java.util.Collections.singletonList(productParams))
                .setObfuscatedAccountId(obfuscateAccountId(user.getUid()))
                .build();
        BillingResult result = billingClient.launchBillingFlow(this, params);
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            sendBillingMessage(false, PLAY_TESTING_NOTICE);
        }
    }

    private String obfuscateAccountId(String uid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(uid.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.US, "%02x", value));
            return hex.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void queryOwnedSubscriptions() {
        if (billingClient == null || !billingClient.isReady()) return;
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS).build();
        billingClient.queryPurchasesAsync(params, (result, purchases) -> {
            boolean reportRestore = restoreRequested;
            restoreRequested = false;
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                boolean found = false;
                for (Purchase purchase : purchases) {
                    if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED
                            && hasFieldWorksProduct(purchase)) {
                        found = true;
                        processSubscriptionPurchase(purchase);
                    }
                }
                if (!found) {
                    syncSubscriptionEntitlement();
                    if (reportRestore) {
                        sendBillingMessage(false, "Checking subscriptions linked to this FieldWorks account.");
                    }
                }
            } else {
                syncSubscriptionEntitlement();
                if (reportRestore) sendBillingMessage(false, PLAY_TESTING_NOTICE);
            }
        });
    }

    private boolean hasFieldWorksProduct(Purchase purchase) {
        for (String product : purchase.getProducts()) {
            for (String expected : SUBSCRIPTION_PRODUCT_IDS) {
                if (expected.equals(product)) return true;
            }
        }
        return false;
    }

    private interface SubscriptionServerCallback {
        void onResponse(JSONObject response);
    }

    private void syncSubscriptionEntitlement() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            sendBillingEntitlement(false, "", "");
            return;
        }
        final String uid = user.getUid();
        JSONObject request = new JSONObject();
        try { request.put("action", "status"); } catch (Exception ignored) {}
        callSubscriptionServer(request, uid, response -> {
            boolean active = response.optBoolean("active", false);
            sendBillingEntitlement(active, active ? response.optString("productId", "") : "", uid);
        });
    }

    private void processSubscriptionPurchase(Purchase purchase) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED
                || !hasFieldWorksProduct(purchase)) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            sendBillingMessage(false, "Sign in to a FieldWorks account before restoring a subscription.");
            return;
        }
        final String uid = user.getUid();
        String productId = purchase.getProducts().isEmpty() ? "" : purchase.getProducts().get(0);
        JSONObject request = new JSONObject();
        try {
            request.put("action", "verify");
            request.put("purchaseToken", purchase.getPurchaseToken());
            request.put("productId", productId);
        } catch (Exception ignored) {}
        callSubscriptionServer(request, uid, response -> {
            if (!response.optBoolean("active", false)) {
                syncSubscriptionEntitlement();
                sendBillingMessage(false, "Google Play did not confirm an active subscription for this account.");
                return;
            }
            if (purchase.isAcknowledged()) {
                sendBillingEntitlement(true, productId, uid);
                return;
            }
            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken()).build();
            billingClient.acknowledgePurchase(params, result -> {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    sendBillingEntitlement(true, productId, uid);
                } else {
                    sendBillingMessage(false, "Payment was verified, but Google Play acknowledgement failed. Tap Restore purchase.");
                }
            });
        });
    }

    private void callSubscriptionServer(JSONObject request, String expectedUid,
            SubscriptionServerCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !expectedUid.equals(user.getUid())) {
            sendBillingEntitlement(false, "", expectedUid);
            return;
        }
        user.getIdToken(false).addOnSuccessListener(tokenResult -> {
            String idToken = tokenResult.getToken();
            if (idToken == null || idToken.isEmpty()) {
                sendBillingEntitlement(false, "", expectedUid);
                sendBillingMessage(false, "Could not authenticate subscription verification. Please sign in again.");
                return;
            }
            subscriptionExecutor.execute(() -> {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(SUBSCRIPTION_FUNCTION_URL).openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(20000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setRequestProperty("Authorization", "Bearer " + idToken);
                    byte[] body = request.toString().getBytes("UTF-8");
                    connection.setFixedLengthStreamingMode(body.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(body);
                    }
                    int status = connection.getResponseCode();
                    InputStream stream = status >= 200 && status < 400
                            ? connection.getInputStream() : connection.getErrorStream();
                    StringBuilder responseText = new StringBuilder();
                    if (stream != null) {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(stream, "UTF-8"))) {
                            String line;
                            while ((line = reader.readLine()) != null) responseText.append(line);
                        }
                    }
                    JSONObject response = responseText.length() > 0
                            ? new JSONObject(responseText.toString()) : new JSONObject();
                    String responseUid = response.optString("uid", "");
                    if (status < 200 || status >= 300 || !expectedUid.equals(responseUid)) {
                        String message = response.optString("message",
                                "Subscription verification is temporarily unavailable.");
                        runOnUiThread(() -> {
                            sendBillingEntitlement(false, "", expectedUid);
                            sendBillingMessage(false, message);
                        });
                    } else {
                        runOnUiThread(() -> callback.onResponse(response));
                    }
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        sendBillingEntitlement(false, "", expectedUid);
                        sendBillingMessage(false,
                                "Could not verify the subscription with Google Play. Check your connection and try again.");
                    });
                } finally {
                    if (connection != null) connection.disconnect();
                }
            });
        }).addOnFailureListener(error -> {
            sendBillingEntitlement(false, "", expectedUid);
            sendBillingMessage(false, "Could not authenticate subscription verification. Please sign in again.");
        });
    }

    private void sendBillingCallback(String callback, JSONObject payload) {
        if (webView == null) return;
        runOnUiThread(() -> webView.evaluateJavascript(
                "window." + callback + " && window." + callback + "(" + payload + ")", null));
    }

    private void sendBillingMessage(boolean success, String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("success", success);
            payload.put("message", message == null ? "" : message);
        } catch (Exception ignored) {}
        sendBillingCallback("onFieldWorksBillingMessage", payload);
    }

    private void sendBillingEntitlement(boolean active, String productId, String uid) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("active", active);
            payload.put("productId", productId == null ? "" : productId);
            payload.put("uid", uid == null ? "" : uid);
        } catch (Exception ignored) {}
        sendBillingCallback("onFieldWorksBillingEntitlement", payload);
    }

    private final class AuthBridge {
        @JavascriptInterface
        public String currentUid() {
            try {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                return user == null ? "" : user.getUid();
            } catch (Exception error) { return ""; }
        }

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
        public void startGoogle() {
            runOnUiThread(() -> launchGoogleSignIn());
        }

        @JavascriptInterface
        public void startFacebook() {
            runOnUiThread(() -> {
                OAuthProvider provider = OAuthProvider.newBuilder("facebook.com").build();
                FirebaseAuth.getInstance()
                        .startActivityForSignInWithProvider(MainActivity.this, provider)
                        .addOnCompleteListener(task -> {
                            if (task.isSuccessful()) {
                                FirebaseUser user = task.getResult().getUser();
                                sendAuthResult(true, "Signed in with Facebook.",
                                        user == null ? "" : user.getEmail(), "");
                            } else {
                                sendAuthResult(false, authErrorMessage(task.getException()), "", "");
                            }
                        });
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
        webView.addJavascriptInterface(new BillingBridge(), "FieldWorksBilling");
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

    private void sendAuthResult(boolean success, String message, String email) {
        sendAuthResult(success, message, email, "");
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
    @Override
    protected void onDestroy() {
        if (billingClient != null) billingClient.endConnection();
        subscriptionExecutor.shutdownNow();
        webView = null;
        super.onDestroy();
    }
}
