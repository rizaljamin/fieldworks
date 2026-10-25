package com.fieldworks.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;

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
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private File captureFile;
    private String captureKind;
    private int captureProjectId;
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
        public void removeCapture(String name) {
            if (name == null || name.contains("/") || name.contains("\\")) return;
            File directory = new File(getFilesDir(), "fieldworks_media");
            File file = new File(directory, name);
            if (file.getParentFile().equals(directory)) file.delete();
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

    private void notifyCameraError(String message) {
        if (webView != null) {
            webView.evaluateJavascript("window.toast && window.toast("
                    + JSONObject.quote(message) + ")", null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
