package com.fieldworks.app;

import android.app.Activity;
import android.Manifest;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
    private static final int CAMERA_PHOTO_REQUEST = 2301;
    private static final int CAMERA_VIDEO_REQUEST = 2302;
    private static final int MEDIA_PICKER_REQUEST = 2303;
    private static final int AUDIO_PERMISSION_REQUEST = 2304;
    private static final int MAX_MEDIA_PER_PICK = 5;
    private WebView webView;
    private File captureFile;
    private String captureKind = "";
    private String captureProjectId = "";
    private String importKind = "";
    private String importProjectId = "";
    private MediaRecorder voiceRecorder;
    private File voiceFile;
    private String voiceProjectId = "";
    private boolean voicePermissionPending;
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
    private final class MediaBridge {
        @JavascriptInterface
        public void capture(String kind, String projectId) {
            runOnUiThread(() -> launchMediaCapture(kind, projectId));
        }

        @JavascriptInterface
        public void pickMedia(String kind, String projectId) {
            runOnUiThread(() -> launchMediaPicker(kind, projectId));
        }

        @JavascriptInterface
        public void startVoice(String projectId) {
            runOnUiThread(() -> requestVoicePermissionAndStart(projectId));
        }

        @JavascriptInterface
        public void stopVoice() {
            runOnUiThread(() -> finishVoiceRecording(true));
        }

        @JavascriptInterface
        public void cancelVoice() {
            runOnUiThread(() -> {
                if (voicePermissionPending) {
                    voicePermissionPending = false;
                    voiceProjectId = "";
                    notifyVoiceStatus("canceled", "Recording canceled.");
                } else {
                    finishVoiceRecording(false);
                }
            });
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

    private File mediaDirectory() {
        File directory = new File(getFilesDir(), "fieldworks_media");
        if (!directory.exists() && !directory.mkdirs()) return null;
        return directory;
    }

    private void launchMediaCapture(String kind, String projectId) {
        boolean video = "video".equals(kind);
        captureKind = video ? "video" : "photo";
        captureProjectId = projectId == null ? "" : projectId;
        File directory = mediaDirectory();
        if (directory == null) {
            notifyMediaError(captureProjectId, "Could not prepare FieldWorks media storage.");
            return;
        }
        try {
            captureFile = File.createTempFile("fieldworks_capture_", video ? ".mp4" : ".jpg", directory);
            Uri outputUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", captureFile);
            Intent camera = new Intent(video ? MediaStore.ACTION_VIDEO_CAPTURE : MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
            camera.setClipData(ClipData.newRawUri("FieldWorks capture", outputUri));
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(camera, video ? CAMERA_VIDEO_REQUEST : CAMERA_PHOTO_REQUEST);
        } catch (Exception error) {
            if (captureFile != null) captureFile.delete();
            captureFile = null;
            notifyMediaError(captureProjectId, "No camera app is available to capture this moment.");
        }
    }

    private void launchMediaPicker(String kind, String projectId) {
        importKind = "video".equals(kind) ? "video" : "photo";
        importProjectId = projectId == null ? "" : projectId;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("video".equals(importKind) ? "video/*" : "image/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(picker, MEDIA_PICKER_REQUEST);
        } catch (Exception error) {
            notifyMediaError(importProjectId, "Could not open the device media picker.");
        }
    }

    private String mediaDisplayName(Uri uri) {
        String name = "";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) {
            name = "FieldWorks " + System.currentTimeMillis()
                    + ("video".equals(importKind) ? ".mp4" : ".jpg");
        }
        return name.replaceAll("[\\\\/\\r\\n]", "_");
    }

    private File copySelectedMedia(Uri uri, String kind) throws IOException {
        File directory = mediaDirectory();
        if (directory == null) throw new IOException("Could not prepare media storage.");
        String displayName = mediaDisplayName(uri);
        String extension = "";
        int dot = displayName.lastIndexOf('.');
        if (dot >= 0 && dot < displayName.length() - 1) {
            String candidate = displayName.substring(dot).toLowerCase()
                    .replaceAll("[^a-z0-9.]", "");
            if (candidate.length() <= 10) extension = candidate;
        }
        if (extension.isEmpty()) {
            String mime = getContentResolver().getType(uri);
            String guessed = mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            extension = "." + (guessed != null ? guessed : ("video".equals(kind) ? "mp4" : "jpg"));
        }
        File target = File.createTempFile("fieldworks_import_", extension, directory);
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("Could not read the selected media.");
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
        } catch (IOException error) {
            target.delete();
            throw error;
        }
        if (!target.isFile() || target.length() == 0) {
            target.delete();
            throw new IOException("The selected media file was empty.");
        }
        return target;
    }

    private java.util.List<Uri> selectedMediaUris(Intent data) {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data == null) return uris;
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount() && uris.size() < MAX_MEDIA_PER_PICK; i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private File cameraResultFallback(Intent data) {
        try {
            if (data != null && data.getData() != null) return copySelectedMedia(data.getData(), captureKind);
            if (!"photo".equals(captureKind) || data == null || data.getExtras() == null) return null;
            Object thumbnail = data.getExtras().get("data");
            if (!(thumbnail instanceof Bitmap)) return null;
            File directory = mediaDirectory();
            if (directory == null) return null;
            File fallback = File.createTempFile("fieldworks_camera_fallback_", ".jpg", directory);
            try (FileOutputStream output = new FileOutputStream(fallback)) {
                if (!((Bitmap) thumbnail).compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    fallback.delete();
                    return null;
                }
                output.flush();
            }
            return fallback.length() > 0 ? fallback : null;
        } catch (Exception ignored) { return null; }
    }

    private String localMediaUrl(File file) {
        return Uri.fromFile(file).toString();
    }

    private void notifyMediaReady(String projectId, String kind, File file) {
        if (file == null || !file.isFile() || webView == null) return;
        String script = "window.onFieldWorksMediaReady && window.onFieldWorksMediaReady("
                + JSONObject.quote(projectId == null ? "" : projectId) + ","
                + JSONObject.quote(kind == null ? "" : kind) + ","
                + JSONObject.quote(localMediaUrl(file)) + ","
                + JSONObject.quote(file.getName()) + ")";
        webView.evaluateJavascript(script, null);
    }

    private void notifyMediaError(String projectId, String message) {
        if (webView == null) return;
        String script = "window.onFieldWorksMediaError && window.onFieldWorksMediaError("
                + JSONObject.quote(projectId == null ? "" : projectId) + ","
                + JSONObject.quote(message == null ? "" : message) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private void requestVoicePermissionAndStart(String projectId) {
        if (voiceRecorder != null || voicePermissionPending) return;
        voiceProjectId = projectId == null ? "" : projectId;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording();
        } else {
            voicePermissionPending = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
        }
    }

    private void startVoiceRecording() {
        File directory = mediaDirectory();
        if (directory == null) {
            voiceProjectId = "";
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
            notifyVoiceStatus("recording", "Recording… Tap Stop & save when you are done.");
        } catch (Exception error) {
            releaseVoiceRecorder();
            if (voiceFile != null) voiceFile.delete();
            voiceFile = null;
            voiceProjectId = "";
            notifyVoiceStatus("error", "Could not start microphone recording. Check permission and try again.");
        }
    }

    private void finishVoiceRecording(boolean save) {
        if (voicePermissionPending) {
            voicePermissionPending = false;
            voiceProjectId = "";
            notifyVoiceStatus("canceled", "Recording canceled.");
            return;
        }
        if (voiceRecorder == null) return;
        boolean valid = false;
        if (save) {
            try {
                voiceRecorder.stop();
                valid = voiceFile != null && voiceFile.isFile() && voiceFile.length() > 0;
            } catch (RuntimeException error) {
                notifyVoiceStatus("error", "Recording was too short. Record a little longer and try again.");
            }
        }
        releaseVoiceRecorder();
        if (save && valid) {
            notifyMediaReady(voiceProjectId, "voice", voiceFile);
        } else {
            if (voiceFile != null) voiceFile.delete();
            if (!save) notifyVoiceStatus("canceled", "Recording canceled.");
            else notifyVoiceStatus("error", "The voice note could not be saved. Please try again.");
        }
        voiceFile = null;
        voiceProjectId = "";
    }

    private void releaseVoiceRecorder() {
        if (voiceRecorder != null) {
            try { voiceRecorder.reset(); } catch (Exception ignored) { }
            try { voiceRecorder.release(); } catch (Exception ignored) { }
            voiceRecorder = null;
        }
    }

    private void notifyVoiceStatus(String state, String message) {
        if (webView == null) return;
        String script = "window.onFieldWorksVoiceStatus && window.onFieldWorksVoiceStatus("
                + JSONObject.quote(state) + "," + JSONObject.quote(message == null ? "" : message) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MEDIA_PICKER_REQUEST) {
            if (resultCode == RESULT_OK) {
                java.util.List<Uri> uris = selectedMediaUris(data);
                if (uris.isEmpty()) {
                    notifyMediaError(importProjectId, "No media file was selected.");
                    return;
                }
                java.util.List<File> copiedFiles = new java.util.ArrayList<>();
                org.json.JSONArray imports = new org.json.JSONArray();
                try {
                    for (Uri uri : uris) {
                        File file = copySelectedMedia(uri, importKind);
                        copiedFiles.add(file);
                        org.json.JSONObject item = new org.json.JSONObject();
                        item.put("url", localMediaUrl(file));
                        item.put("name", mediaDisplayName(uri));
                        item.put("kind", importKind);
                        imports.put(item);
                    }
                } catch (Exception error) {
                    for (File file : copiedFiles) file.delete();
                    notifyMediaError(importProjectId, "Could not copy the selected media to FieldWorks.");
                    return;
                }
                String script = "window.onFieldWorksImportBatch && window.onFieldWorksImportBatch("
                        + imports + "," + JSONObject.quote(importProjectId) + ")";
                webView.evaluateJavascript(script, null);
            }
            return;
        }
        if (requestCode == CAMERA_PHOTO_REQUEST || requestCode == CAMERA_VIDEO_REQUEST) {
            File result = captureFile;
            if (resultCode == RESULT_OK && (result == null || !result.isFile() || result.length() == 0)) {
                result = cameraResultFallback(data);
                if (captureFile != null) captureFile.delete();
            }
            if (resultCode == RESULT_OK && result != null && result.isFile() && result.length() > 0) {
                notifyMediaReady(captureProjectId, captureKind, result);
            } else if (resultCode == RESULT_OK) {
                notifyMediaError(captureProjectId, "The camera returned no saved media. Try again or choose an existing file.");
                if (captureFile != null) captureFile.delete();
            } else if (captureFile != null) {
                captureFile.delete();
            }
            captureFile = null;
            captureProjectId = "";
            captureKind = "";
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != AUDIO_PERMISSION_REQUEST || !voicePermissionPending) return;
        voicePermissionPending = false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording();
        } else {
            voiceProjectId = "";
            notifyVoiceStatus("error", "Microphone permission is required to record a voice note.");
        }
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
        // Android 15+ enforces edge-to-edge for this target SDK; fit the WebView
        // inside the status, navigation, and tablet taskbar system bars.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient());
        webView.setVerticalScrollBarEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowContentAccess(false);
        webView.addJavascriptInterface(new AuthBridge(), "FieldWorksAuth");
        webView.addJavascriptInterface(new MediaBridge(), "FieldWorksMedia");
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
        if (voiceRecorder != null || voicePermissionPending) finishVoiceRecording(false);
        if (captureFile != null) captureFile.delete();
        if (billingClient != null) billingClient.endConnection();
        subscriptionExecutor.shutdownNow();
        if (webView != null) webView.destroy();
        webView = null;
        super.onDestroy();
    }
}
