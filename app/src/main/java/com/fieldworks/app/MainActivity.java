package com.fieldworks.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaRecorder;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.core.content.FileProvider;
import android.widget.FrameLayout;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.GoogleAuthProvider;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import android.os.CancellationSignal;
import java.util.concurrent.Executor;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static class LocalContentWebViewClient extends WebViewClientCompat {
        private final WebViewAssetLoader assetLoader;

        LocalContentWebViewClient(WebViewAssetLoader assetLoader) {
            this.assetLoader = assetLoader;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return assetLoader.shouldInterceptRequest(request.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !"appassets.androidplatform.net".equals(request.getUrl().getHost());
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return assetLoader.shouldInterceptRequest(Uri.parse(url));
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !"appassets.androidplatform.net".equals(Uri.parse(url).getHost());
        }
    }

    private static final int FILE_CHOOSER_REQUEST = 1201;
    private static final int AUDIO_PERMISSION_REQUEST = 1202;
    private static final int CAMERA_PHOTO_REQUEST = 1203;
    private static final int CAMERA_VIDEO_REQUEST = 1204;
    private static final int MEDIA_PICKER_REQUEST = 1205;
    private static final int NATIVE_AUDIO_PERMISSION_REQUEST = 1206;
    private static final int ARCHIVE_EXPORT_REQUEST = 1207;
    private WebView webView;
    private boolean googleSignInInProgress;
    private CancellationSignal pendingGoogleSignIn;
    private Runnable googleSignInTimeout;
    private BillingClient billingClient;
    private final ExecutorService subscriptionExecutor = Executors.newSingleThreadExecutor();
    private static final String SUBSCRIPTION_FUNCTION_URL = "https://asia-southeast1-fieldworks-512712.cloudfunctions.net/fieldWorksSubscription";
    private volatile boolean restoreRequested;
    private final Map<String, ProductDetails> subscriptionProducts = new HashMap<>();
    private static final String PLAY_TESTING_NOTICE = "Google Play subscription testing requires installing FieldWorks from a Google Play testing track. Purchases or restore may fail when the app is sideloaded.";
    private static final String[] SUBSCRIPTION_PRODUCT_IDS = {
            "fw2026", "fw2026_stu"
    };
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private File captureFile;
    private File archiveTempFile;
    private FileOutputStream archiveOutputStream;
    private String archiveExportName = "";
    private String archiveWriteError = "";
    private String captureKind;
    private MediaRecorder voiceRecorder;
    private File voiceFile;
    private long voiceProjectId;
    private String voiceAlbumId = "";
    private boolean voicePermissionPending;
    private long captureProjectId;
    private long importProjectId;
    private String importKind;
    private volatile String pendingFileSource = "folders";

    private final class AppInfoBridge {
        @JavascriptInterface
        public String getVersionName() {
            try {
                String versionName = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
                return versionName == null ? "" : versionName;
            } catch (PackageManager.NameNotFoundException e) {
                return "";
            }
        }
    }

    private final class MediaBridge {
        @JavascriptInterface
        public void prepareSource(String source) {
            pendingFileSource = "drive".equals(source) ? "drive" : "folders";
        }

        @JavascriptInterface
        public void capture(String kind, long projectId) {
            runOnUiThread(() -> launchCamera(kind, projectId));
        }

        @JavascriptInterface
        public void pickMedia(String kind, long projectId) {
            runOnUiThread(() -> launchMediaPicker(kind, projectId));
        }

        @JavascriptInterface
        public void startVoice(long projectId, String albumId) {
            runOnUiThread(() -> requestVoicePermissionAndStart(projectId, albumId));
        }

        @JavascriptInterface
        public void stopVoice() {
            runOnUiThread(() -> finishVoiceRecording(true));
        }

        @JavascriptInterface
        public void cancelVoice() {
            runOnUiThread(() -> finishVoiceRecording(false));
        }

        @JavascriptInterface
        public void beginArchiveExport(String fileName) {
            runOnUiThread(() -> beginProjectArchiveExport(fileName));
        }

        @JavascriptInterface
        public synchronized boolean writeArchiveChunk(String base64Chunk) {
            if (archiveOutputStream == null || !archiveWriteError.isEmpty()) return false;
            try {
                byte[] chunk = Base64.decode(base64Chunk, Base64.DEFAULT);
                archiveOutputStream.write(chunk);
                return true;
            } catch (Exception error) {
                archiveWriteError = "Could not write a backup chunk. Check device storage space.";
                return false;
            }
        }

        @JavascriptInterface
        public void finishArchiveExport() {
            runOnUiThread(() -> showProjectArchiveSavePicker());
        }

        @JavascriptInterface
        public void cancelArchiveExport() {
            runOnUiThread(() -> {
                closeArchiveOutput();
                deleteArchiveTempFile();
            });
        }

        @JavascriptInterface
        public void removeCapture(String name) {
            if (name == null || name.contains("/") || name.contains("\\")) return;
            File directory = new File(getFilesDir(), "fieldworks_media");
            File file = new File(directory, name);
            if (file.getParentFile().equals(directory)) file.delete();
        }
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
            // Keep authentication errors inside the login screen.
        }
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
        return "Could not sign in: " + (error == null ? "Please try again." : error.getLocalizedMessage());
    }

    private String resourceString(String name) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id == 0 ? "" : getString(id);
    }

    private boolean isActiveGoogleSignIn(CancellationSignal signal) {
        return googleSignInInProgress && pendingGoogleSignIn == signal;
    }

    private void clearGoogleSignInTimeout(CancellationSignal signal) {
        if (pendingGoogleSignIn != signal) return;
        if (webView != null && googleSignInTimeout != null) {
            webView.removeCallbacks(googleSignInTimeout);
        }
        googleSignInTimeout = null;
    }

    private void finishGoogleSignIn(CancellationSignal signal, boolean success, String message, String email) {
        if (!isActiveGoogleSignIn(signal)) return;
        clearGoogleSignInTimeout(signal);
        googleSignInInProgress = false;
        pendingGoogleSignIn = null;
        sendAuthResult(success, message, email);
    }

    private void handleGoogleCredential(GetCredentialResponse response, CancellationSignal signal) {
        if (!isActiveGoogleSignIn(signal)) return;
        Credential credential = response.getCredential();
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    .equals(credential.getType())) {
            finishGoogleSignIn(signal, false, "Google returned an unsupported sign-in credential.", "");
            return;
        }
        try {
            GoogleIdTokenCredential googleCredential =
                    GoogleIdTokenCredential.createFrom(credential.getData());
            AuthCredential firebaseCredential = GoogleAuthProvider.getCredential(
                    googleCredential.getIdToken(), null);
            FirebaseAuth.getInstance().signInWithCredential(firebaseCredential)
                    .addOnCompleteListener(task -> {
                        if (!isActiveGoogleSignIn(signal)) return;
                        if (task.isSuccessful()) {
                            FirebaseUser user = task.getResult().getUser();
                            finishGoogleSignIn(signal, true, "Signed in with Google.",
                                    user == null ? googleCredential.getId() : user.getEmail());
                        } else {
                            finishGoogleSignIn(signal, false, authErrorMessage(task.getException()), "");
                        }
                    });
        } catch (Exception error) {
            finishGoogleSignIn(signal, false, "Google returned an invalid sign-in response: "
                    + (error.getLocalizedMessage() == null ? "Please try again." : error.getLocalizedMessage()), "");
        }
    }

    private void launchGoogleButtonFlow(CredentialManager manager, String clientId, CancellationSignal signal) {
        GetSignInWithGoogleOption googleOption =
                new GetSignInWithGoogleOption.Builder(clientId).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleOption)
                .build();
        manager.getCredentialAsync(this, request, signal, getMainExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        if (!isActiveGoogleSignIn(signal)) return;
                        clearGoogleSignInTimeout(signal);
                        handleGoogleCredential(response, signal);
                    }

                    @Override
                    public void onError(GetCredentialException error) {
                        String message = error.getLocalizedMessage();
                        if (message == null || message.trim().isEmpty()) {
                            message = "Google account selection was cancelled or could not start.";
                        }
                        finishGoogleSignIn(signal, false, message, "");
                    }
                });
    }

    private void launchGoogleSignIn() {
        if (googleSignInInProgress) return;
        String clientId = resourceString("default_web_client_id");
        if (clientId.isEmpty()) {
            sendAuthResult(false, "Google setup is incomplete. Download the refreshed Firebase config and rebuild.", "");
            return;
        }

        CancellationSignal signal = new CancellationSignal();
        googleSignInInProgress = true;
        pendingGoogleSignIn = signal;
        googleSignInTimeout = () -> {
            if (!isActiveGoogleSignIn(signal)) return;
            googleSignInInProgress = false;
            pendingGoogleSignIn = null;
            googleSignInTimeout = null;
            signal.cancel();
            sendAuthResult(false,
                    "Google sign-in did not respond. Check Google Play services and try again.", "");
        };
        if (webView != null) {
            webView.postDelayed(googleSignInTimeout, 30000);
        }

        try {
            CredentialManager manager = CredentialManager.create(this);
            launchGoogleButtonFlow(manager, clientId, signal);
        } catch (Exception error) {
            finishGoogleSignIn(signal, false, "Google sign-in could not start: "
                    + (error.getLocalizedMessage() == null ? "Please try again." : error.getLocalizedMessage()), "");
        }
    }
    private final class AuthBridge {
        @JavascriptInterface
        public void signIn(String email, String password) {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            FirebaseUser user = task.getResult().getUser();
                            sendAuthResult(true, "Welcome back.", user == null ? email : user.getEmail());
                        } else {
                            sendAuthResult(false, authErrorMessage(task.getException()), email);
                        }
                    });
        }

        @JavascriptInterface
        public void createAccount(String name, String email, String password) {
            FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful()) {
                            sendAuthResult(false, authErrorMessage(task.getException()), email);
                            return;
                        }
                        FirebaseUser user = task.getResult().getUser();
                        if (user == null) {
                            sendAuthResult(false, "Account created, but the session could not be opened.", email);
                            return;
                        }
                        String displayName = name == null ? "" : name.trim();
                        if (displayName.isEmpty()) {
                            sendAuthResult(true, "Your FieldWorks account is ready.", user.getEmail());
                            return;
                        }
                        UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                                .setDisplayName(displayName).build();
                        user.updateProfile(profile).addOnCompleteListener(profileTask ->
                                sendAuthResult(true, "Your FieldWorks account is ready.", user.getEmail()));
                    });
        }

        @JavascriptInterface
        public void startGoogle() {
            runOnUiThread(() -> launchGoogleSignIn());
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

        @JavascriptInterface
        public String currentEmail() {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            return user == null ? "" : (user.getEmail() == null ? "" : user.getEmail());
        }
        
        @JavascriptInterface
        public String currentUid() {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            return user == null ? "" : user.getUid();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            importProjectId = savedInstanceState.getLong("fieldworks.importProjectId", 0L);
            importKind = savedInstanceState.getString("fieldworks.importKind");
            captureProjectId = savedInstanceState.getLong("fieldworks.captureProjectId", 0L);
            captureKind = savedInstanceState.getString("fieldworks.captureKind");
            String capturePath = savedInstanceState.getString("fieldworks.captureFile");
            if (capturePath != null) captureFile = new File(capturePath);
        }
        getWindow().setStatusBarColor(android.graphics.Color.rgb(247, 244, 235));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(8, 44, 80));

        webView = new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.rgb(247, 244, 235));
        FrameLayout appContainer = new FrameLayout(this);
        appContainer.setBackgroundColor(android.graphics.Color.rgb(247, 244, 235));
        ViewCompat.setOnApplyWindowInsetsListener(appContainer, (view, windowInsets) -> {
            Insets safeInsets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom);
            return windowInsets;
        });
        new WindowInsetsControllerCompat(getWindow(), appContainer).setAppearanceLightStatusBars(true);
        appContainer.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        File mediaDirectory = new File(getFilesDir(), "fieldworks_media");
        if (!mediaDirectory.exists()) mediaDirectory.mkdirs();
        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/res/", new WebViewAssetLoader.ResourcesPathHandler(this))
                .addPathHandler("/media/", new WebViewAssetLoader.InternalStoragePathHandler(
                        this, mediaDirectory))
                .build();
        webView.setWebViewClient(new LocalContentWebViewClient(assetLoader));
        webView.addJavascriptInterface(new MediaBridge(), "FieldWorksMedia");
        webView.addJavascriptInterface(new AppInfoBridge(), "FieldWorksAppInfo");
        webView.addJavascriptInterface(new AuthBridge(), "FieldWorksAuth");
        webView.addJavascriptInterface(new BillingBridge(), "FieldWorksBilling");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                String source = pendingFileSource;
                pendingFileSource = "folders";
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                String title;
                if ("archive".equals(source)) {
                    picker.setType("application/zip");
                    picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                    title = "Choose a FieldWorks project backup";
                } else {
                    picker.setType("*/*");
                    picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                    picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    title = "drive".equals(source)
                            ? "Choose from Google Drive or another location"
                            : "Browse device folders or cloud locations";
                }
                try {
                    startActivityForResult(Intent.createChooser(picker, title),
                            FILE_CHOOSER_REQUEST);
                } catch (Exception error) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean audioRequested = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            audioRequested = true;
                        }
                    }
                    if (!audioRequested) {
                        request.deny();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingPermission = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                                AUDIO_PERMISSION_REQUEST);
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingPermission == request) pendingPermission = null;
            }
        });
        setContentView(appContainer);
        ViewCompat.requestApplyInsets(appContainer);
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private synchronized void beginProjectArchiveExport(String fileName) {
        closeArchiveOutput();
        deleteArchiveTempFile();
        archiveWriteError = "";
        archiveExportName = fileName == null ? "FieldWorks-projects.zip" : fileName
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (!archiveExportName.toLowerCase().endsWith(".zip")) archiveExportName += ".zip";
        try {
            archiveTempFile = new File(getCacheDir(), "fieldworks-project-archive.zip");
            archiveOutputStream = new FileOutputStream(archiveTempFile, false);
            notifyArchiveExportStarted(true, "");
        } catch (Exception error) {
            closeArchiveOutput();
            deleteArchiveTempFile();
            notifyArchiveExportStarted(false, "Could not prepare the backup file. Check device storage space.");
        }
    }

    private void showProjectArchiveSavePicker() {
        synchronized (this) {
            closeArchiveOutput();
            if (!archiveWriteError.isEmpty()) {
                String message = archiveWriteError;
                deleteArchiveTempFile();
                notifyArchiveExportResult(false, message);
                return;
            }
            if (archiveTempFile == null || !archiveTempFile.isFile()) {
                notifyArchiveExportResult(false, "The backup file could not be created.");
                return;
            }
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/zip");
        create.putExtra(Intent.EXTRA_TITLE, archiveExportName);
        try {
            startActivityForResult(create, ARCHIVE_EXPORT_REQUEST);
        } catch (Exception error) {
            closeArchiveOutput();
            deleteArchiveTempFile();
            notifyArchiveExportResult(false, "No file or cloud storage provider is available to save the backup.");
        }
    }

    private void notifyArchiveExportStarted(boolean success, String message) {
        if (webView == null) return;
        webView.evaluateJavascript("window.onFieldWorksArchiveExportStarted && window.onFieldWorksArchiveExportStarted("
                + success + "," + JSONObject.quote(message) + ")", null);
    }

    private void notifyArchiveExportResult(boolean success, String message) {
        if (webView == null) return;
        webView.evaluateJavascript("window.onFieldWorksArchiveExportResult && window.onFieldWorksArchiveExportResult("
                + success + "," + JSONObject.quote(message) + ")", null);
    }

    private synchronized void closeArchiveOutput() {
        if (archiveOutputStream != null) {
            try { archiveOutputStream.close(); } catch (IOException ignored) {}
            archiveOutputStream = null;
        }
    }

    private synchronized void deleteArchiveTempFile() {
        if (archiveTempFile != null) {
            archiveTempFile.delete();
            archiveTempFile = null;
        }
    }

    private void requestVoicePermissionAndStart(long projectId, String albumId) {
        if (projectId <= 0 || voiceRecorder != null || voicePermissionPending) return;
        voiceProjectId = projectId;
        voiceAlbumId = albumId == null ? "" : albumId;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording();
        } else {
            voicePermissionPending = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    NATIVE_AUDIO_PERMISSION_REQUEST);
        }
    }

    private void startVoiceRecording() {
        File directory = new File(getFilesDir(), "fieldworks_media");
        if (!directory.exists() && !directory.mkdirs()) {
            notifyVoiceStatus("error", "Could not prepare FieldWorks audio storage.");
            return;
        }
        try {
            voiceFile = File.createTempFile("fieldworks_voice_", ".m4a", directory);
            MediaRecorder recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(voiceFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            voiceRecorder = recorder;
            notifyVoiceStatus("recording", "");
        } catch (Exception error) {
            releaseVoiceRecorder();
            if (voiceFile != null) voiceFile.delete();
            voiceFile = null;
            voiceProjectId = 0L;
            voiceAlbumId = "";
            notifyVoiceStatus("error", "Could not start microphone recording. Check microphone permission and try again.");
        }
    }

    private void finishVoiceRecording(boolean save) {
        if (voicePermissionPending) {
            voicePermissionPending = false;
            voiceProjectId = 0L;
            voiceAlbumId = "";
            notifyVoiceStatus("canceled", "Recording canceled.");
            return;
        }
        if (voiceRecorder == null) {
            if (!save) notifyVoiceStatus("canceled", "Recording canceled.");
            return;
        }
        boolean valid = false;
        if (save) {
            try {
                voiceRecorder.stop();
                valid = voiceFile != null && voiceFile.isFile() && voiceFile.length() > 0;
            } catch (RuntimeException error) {
                notifyVoiceStatus("error", "Recording was too short. Record for a moment longer and try again.");
            }
        }
        releaseVoiceRecorder();
        if (save && valid) {
            String url = "https://appassets.androidplatform.net/media/" + Uri.encode(voiceFile.getName());
            String script = "window.onFieldWorksVoiceCapture && window.onFieldWorksVoiceCapture("
                    + JSONObject.quote(url) + "," + JSONObject.quote(voiceFile.getName()) + ","
                    + voiceProjectId + "," + JSONObject.quote(voiceAlbumId) + ")";
            if (webView != null) webView.evaluateJavascript(script, null);
        } else {
            if (voiceFile != null) voiceFile.delete();
            notifyVoiceStatus(save ? "error" : "canceled",
                    save ? "The recording could not be saved. Please try again." : "Recording canceled.");
        }
        voiceFile = null;
        voiceProjectId = 0L;
        voiceAlbumId = "";
    }

    private void releaseVoiceRecorder() {
        if (voiceRecorder != null) {
            try { voiceRecorder.reset(); } catch (Exception ignored) { }
            try { voiceRecorder.release(); } catch (Exception ignored) { }
            voiceRecorder = null;
        }
    }

    private void notifyVoiceStatus(String state, String message) {
        if (webView != null) {
            webView.evaluateJavascript("window.onFieldWorksVoiceStatus && window.onFieldWorksVoiceStatus("
                    + JSONObject.quote(state) + "," + JSONObject.quote(message) + ")", null);
        }
    }

    private void launchCamera(String kind, long projectId) {
        boolean video = "video".equals(kind);
        captureKind = video ? "video" : "photo";
        captureProjectId = projectId;
        File directory = new File(getFilesDir(), "fieldworks_media");
        if (!directory.exists() && !directory.mkdirs()) {
            notifyCameraError("Could not prepare a place to save the capture.");
            return;
        }
        try {
            captureFile = File.createTempFile("fieldworks_", video ? ".mp4" : ".jpg", directory);
            Uri outputUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", captureFile);
            Intent camera = new Intent(video ? MediaStore.ACTION_VIDEO_CAPTURE
                    : MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
            camera.setClipData(ClipData.newRawUri("FieldWorks capture", outputUri));
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(camera, video ? CAMERA_VIDEO_REQUEST : CAMERA_PHOTO_REQUEST);
        } catch (IOException | android.content.ActivityNotFoundException error) {
            if (captureFile != null) captureFile.delete();
            captureFile = null;
            notifyCameraError("No camera app is available to capture this moment.");
        }
    }

    private File cameraResultFallback(Intent data) {
        try {
            importKind = captureKind;
            if (data != null && data.getData() != null) return copyImportedMedia(data.getData());
            if (!"photo".equals(captureKind) || data == null || data.getExtras() == null) return null;
            Object thumbnail = data.getExtras().get("data");
            if (!(thumbnail instanceof Bitmap)) return null;
            File directory = new File(getFilesDir(), "fieldworks_media");
            if (!directory.exists() && !directory.mkdirs()) return null;
            File fallback = File.createTempFile("fieldworks_camera_fallback_", ".jpg", directory);
            try (FileOutputStream output = new FileOutputStream(fallback)) {
                if (!((Bitmap) thumbnail).compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    fallback.delete();
                    return null;
                }
                output.flush();
            }
            return fallback.length() > 0 ? fallback : null;
        } catch (Exception error) {
            return null;
        }
    }

    private void launchMediaPicker(String kind, long projectId) {
        boolean video = "video".equals(kind);
        importKind = video ? "video" : "photo";
        importProjectId = projectId;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType(video ? "video/*" : "image/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(picker, MEDIA_PICKER_REQUEST);
        } catch (Exception error) {
            notifyCameraError("Could not open the photo or video picker (" + error.getClass().getSimpleName() + "). " + (error.getMessage() == null ? "" : error.getMessage()));
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception ignored) {
            // Fall back to a generated name when a provider omits display metadata.
        }
        if (name == null || name.trim().isEmpty()) {
            name = "fieldworks-" + System.currentTimeMillis()
                    + ("video".equals(importKind) ? ".mp4" : ".jpg");
        }
        return name.replaceAll("[\\\\/\\r\\n]", "_");
    }

    private File copyImportedMedia(Uri uri) throws IOException {
        File directory = new File(getFilesDir(), "fieldworks_media");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not prepare FieldWorks media storage.");
        }
        String originalName = displayName(uri);
        String extension = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0 && dot < originalName.length() - 1) {
            String candidate = originalName.substring(dot).toLowerCase()
                    .replaceAll("[^a-z0-9.]", "");
            if (candidate.length() <= 10) extension = candidate;
        }
        if (extension.isEmpty()) {
            String mime = getContentResolver().getType(uri);
            String guessed = mime == null ? null : android.webkit.MimeTypeMap
                    .getSingleton().getExtensionFromMimeType(mime);
            extension = "." + (guessed != null ? guessed
                    : ("video".equals(importKind) ? "mp4" : "jpg"));
        }
        File target = File.createTempFile("fieldworks_import_", extension, directory);
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("Could not read the selected media.");
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
        } catch (IOException error) {
            target.delete();
            throw error;
        }
        if (target.length() == 0) {
            target.delete();
            throw new IOException("The selected media file was empty.");
        }
        return target;
    }

    private List<Uri> selectedUris(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data == null) return uris;
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private void importSelectedMedia(Intent data) {
        if (importProjectId <= 0) {
            notifyCameraError("Project selection expired. Reopen the project and choose media again.");
            return;
        }
        List<Uri> uris = selectedUris(data);
        if (uris.isEmpty()) {
            notifyCameraError("No media was selected.");
            return;
        }
        JSONArray imports = new JSONArray();
        List<File> copiedFiles = new ArrayList<>();
        for (Uri uri : uris) {
            try {
                File file = copyImportedMedia(uri);
                copiedFiles.add(file);
                JSONObject item = new JSONObject();
                item.put("url", "https://appassets.androidplatform.net/media/"
                        + Uri.encode(file.getName()));
                item.put("name", displayName(uri));
                item.put("kind", importKind);
                imports.put(item);
            } catch (Exception error) {
                for (File file : copiedFiles) file.delete();
                notifyCameraError("Media import failed (" + error.getClass().getSimpleName() + "). " + (error.getMessage() == null ? "Please try another file." : error.getMessage()));
                return;
            }
        }
        String script = "window.onFieldWorksImportBatch && window.onFieldWorksImportBatch("
                + imports + "," + importProjectId + ")";
        webView.evaluateJavascript(script, null);
    }

    private void notifyCameraError(String message) {
        if (webView != null) {
            webView.evaluateJavascript("window.toast && window.toast("
                    + JSONObject.quote(message) + ")", null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putLong("fieldworks.importProjectId", importProjectId);
        outState.putString("fieldworks.importKind", importKind);
        outState.putLong("fieldworks.captureProjectId", captureProjectId);
        outState.putString("fieldworks.captureKind", captureKind);
        if (captureFile != null) outState.putString("fieldworks.captureFile", captureFile.getAbsolutePath());
        super.onSaveInstanceState(outState);
    }

@Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MEDIA_PICKER_REQUEST) {
            if (resultCode == RESULT_OK) importSelectedMedia(data);
            return;
        }
        if (requestCode == CAMERA_PHOTO_REQUEST || requestCode == CAMERA_VIDEO_REQUEST) {
            if (resultCode == RESULT_OK
                    && (captureFile == null || !captureFile.isFile() || captureFile.length() == 0)) {
                File fallback = cameraResultFallback(data);
                if (captureFile != null) captureFile.delete();
                captureFile = fallback;
            }
            if (resultCode == RESULT_OK && captureFile != null
                    && captureFile.isFile() && captureFile.length() > 0) {
                String url = "https://appassets.androidplatform.net/media/"
                        + Uri.encode(captureFile.getName());
                String script = "window.onFieldWorksCapture && window.onFieldWorksCapture("
                        + JSONObject.quote(url) + ","
                        + JSONObject.quote(captureKind) + ","
                        + JSONObject.quote(captureFile.getName()) + ","
                        + captureProjectId + ")";
                webView.evaluateJavascript(script, null);
            } else {
                if (resultCode == RESULT_OK) {
                    notifyCameraError("Camera returned no saved media. Retry or choose an existing photo.");
                }
                if (captureFile != null) captureFile.delete();
            }
            captureFile = null;
            return;
        }
        if (requestCode == ARCHIVE_EXPORT_REQUEST) {
            File source;
            synchronized (this) { source = archiveTempFile; }
            Uri destination = resultCode == RESULT_OK && data != null ? data.getData() : null;
            if (destination == null || source == null || !source.isFile()) {
                closeArchiveOutput();
                deleteArchiveTempFile();
                notifyArchiveExportResult(false, resultCode == RESULT_CANCELED
                        ? "Backup save canceled." : "Could not open the selected backup destination.");
                return;
            }
            new Thread(() -> {
                try (InputStream input = new java.io.FileInputStream(source);
                     OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new IOException("The selected destination could not be opened.");
                    byte[] buffer = new byte[32768];
                    int length;
                    while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                    output.flush();
                    runOnUiThread(() -> {
                        deleteArchiveTempFile();
                        notifyArchiveExportResult(true, "");
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        deleteArchiveTempFile();
                        notifyArchiveExportResult(false, "Could not save the backup to that location. Choose another folder or cloud drive.");
                    });
                }
            }, "FieldWorks-archive-export").start();
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NATIVE_AUDIO_PERMISSION_REQUEST) {
            voicePermissionPending = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && voiceProjectId > 0) {
                startVoiceRecording();
            } else {
                voiceProjectId = 0L;
                voiceAlbumId = "";
                notifyVoiceStatus("error", "Microphone permission was denied. Allow it to record a voice note.");
            }
            return;
        }
        if (requestCode == AUDIO_PERMISSION_REQUEST && pendingPermission != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingPermission.deny();
            }
            pendingPermission = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (billingClient != null) billingClient.endConnection();
        if (voiceRecorder != null) finishVoiceRecording(false);
        super.onDestroy();
    }

}
