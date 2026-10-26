package com.fieldworks.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
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
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import android.os.CancellationSignal;
import java.util.concurrent.Executor;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private File captureFile;
    private String captureKind;
    private int captureProjectId;
    private int importProjectId;
    private String importKind;
    private volatile String pendingFileSource = "folders";

    private final class MediaBridge {
        @JavascriptInterface
        public void prepareSource(String source) {
            pendingFileSource = "drive".equals(source) ? "drive" : "folders";
        }

        @JavascriptInterface
        public void capture(String kind, int projectId) {
            runOnUiThread(() -> launchCamera(kind, projectId));
        }

        @JavascriptInterface
        public void pickMedia(String kind, int projectId) {
            runOnUiThread(() -> launchMediaPicker(kind, projectId));
        }

        @JavascriptInterface
        public void removeCapture(String name) {
            if (name == null || name.contains("/") || name.contains("\\")) return;
            File directory = new File(getFilesDir(), "fieldworks_media");
            File file = new File(directory, name);
            if (file.getParentFile().equals(directory)) file.delete();
        }
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
        Executor executor = getMainExecutor();
        manager.getCredentialAsync(this, request, new CancellationSignal(), executor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        Credential credential = response.getCredential();
                        if (!(credential instanceof CustomCredential)
                                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                    .equals(credential.getType())) {
                            sendAuthResult(false, "Google returned an unsupported sign-in credential.", "");
                            return;
                        }
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
                    }

                    @Override
                    public void onError(GetCredentialException error) {
                        String message = error.getLocalizedMessage();
                        if (message == null || message.trim().isEmpty()) {
                            message = "Google sign-in was cancelled or could not start.";
                        }
                        sendAuthResult(false, message, "");
                    }
                });
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
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(8, 44, 80));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(8, 44, 80));

        webView = new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.rgb(247, 244, 235));
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
        webView.addJavascriptInterface(new AuthBridge(), "FieldWorksAuth");
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
                picker.setType("*/*");
                picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                String title = "drive".equals(source)
                        ? "Choose from Google Drive or another location"
                        : "Browse device folders or cloud locations";
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
        setContentView(webView);
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void launchCamera(String kind, int projectId) {
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

    private void launchMediaPicker(String kind, int projectId) {
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
            notifyCameraError("Could not open the photo or video picker.");
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
                notifyCameraError("Could not import the selected media. Please try again.");
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MEDIA_PICKER_REQUEST) {
            if (resultCode == RESULT_OK) importSelectedMedia(data);
            return;
        }
        if (requestCode == CAMERA_PHOTO_REQUEST || requestCode == CAMERA_VIDEO_REQUEST) {
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
            } else if (captureFile != null) {
                captureFile.delete();
            }
            captureFile = null;
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
}
