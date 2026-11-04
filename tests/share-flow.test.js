"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const java = fs.readFileSync("app/src/main/java/com/fieldworks/app/MainActivity.java", "utf8");
const html = fs.readFileSync("app/src/main/assets/index.html", "utf8");
const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
const paths = fs.readFileSync("app/src/main/res/xml/file_paths.xml", "utf8");
const version = fs.readFileSync("fieldworks-version.properties", "utf8");
const workflow = fs.readFileSync(".github/workflows/android-apk.yml", "utf8");
const expected = "FieldWorks - Manage & Monitor your physical projects @ ";
const link = "https://play.google.com/store/apps/details?id=com.fieldworks.app";
const checks = [
  ["share message", java.includes('"'+expected+'" + FIELDWORKS_SHARE_LINK') && java.includes('"'+link+'"')],
  ["share bridge", java.includes("public void share(){runOnUiThread(()->shareFieldWorksApp());}")],
  ["same app icon resource", java.includes("BitmapFactory.decodeResource(") && java.includes("R.drawable.launcher_fieldworks")],
  ["compact landscape image", java.includes("final int cardWidth = 720;") && java.includes("final int cardHeight = 270;") && java.includes("final int logoSize = 170;")],
  ["logo placed in image", java.includes("canvas.drawBitmap(sourceIcon, null, logoArea")],
  ["share card compressed", java.includes("card.compress(Bitmap.CompressFormat.PNG, 100, output)")],
  ["image and caption", java.includes('share.setType("image/png")') && java.includes("share.putExtra(Intent.EXTRA_STREAM, iconUri);") && java.includes("share.putExtra(Intent.EXTRA_TEXT, FIELDWORKS_SHARE_MESSAGE);")],
  ["URI read permission", java.includes("share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);")],
  ["FileProvider in manifest", manifest.includes("androidx.core.content.FileProvider") && manifest.includes('android:grantUriPermissions="true"')],
  ["share icon within FileProvider path", paths.includes('path="fieldworks_media/"') && java.includes('new File(getFilesDir(), "fieldworks_media")')],
  ["icon resource exists", fs.existsSync("app/src/main/res/drawable-nodpi/launcher_fieldworks.png")],
  ["text fallback if image fails", java.includes('share.setType("text/plain")')],
  ["native version method", java.includes("public String versionName()") && java.includes("getPackageManager().getPackageInfo(getPackageName(), 0)")],
  ["web UI reads installed version", html.includes("bridge.versionName()") && html.includes('document.querySelectorAll(".app-version")')],
  ["no old version label", !html.includes("V0.4.00 Build 01")],
  ["correct packaged fallback label", html.includes('<small class="app-version">V0.4.01 Build 26</small>')],
  ["guest and trial copy", (html.match(/FieldWorks Guest & Trial copy/g) || []).length === 2],
  ["no visible debug label", !html.includes("FieldWorks debug menu preview.")],
  ["build number 26", version.includes("buildNumber=26")],
  ["signed APK and AAB tasks", workflow.includes(":app:assembleRelease :app:bundleRelease")],
  ["AAB signature check", workflow.includes('jarsigner -verify "app/build/outputs/bundle/release/$AAB_FILENAME"')],
  ["both separately uploaded", workflow.includes("Upload signed APK") && workflow.includes("Upload signed Play Store AAB")],
  ["same signing configuration for both", workflow.includes('ANDROID_KEYSTORE_PATH=$KEYSTORE_PATH')],
  ["unchanged feedback bridge", java.includes("public void submitFeedback(String feedback, String userid, String requestId)")],
];
for (const [name, ok] of checks) assert.ok(ok, name);
console.log("Share image, version label, APK/AAB signing workflow: PASS");
