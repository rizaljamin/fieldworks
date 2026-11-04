"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const java = fs.readFileSync("app/src/main/java/com/fieldworks/app/MainActivity.java", "utf8");
const html = fs.readFileSync("app/src/main/assets/index.html", "utf8");
const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
const paths = fs.readFileSync("app/src/main/res/xml/file_paths.xml", "utf8");
const version = fs.readFileSync("fieldworks-version.properties", "utf8");
const expected = "FieldWorks - Manage & Monitor your physical projects @ ";
const link = "https://play.google.com/store/apps/details?id=com.fieldworks.app";
const checks = [
  ["share message", java.includes('"'+expected+'" + FIELDWORKS_SHARE_LINK') && java.includes('"'+link+'"')],
  ["share handler from JS", java.includes("public void share(){runOnUiThread(()->shareFieldWorksApp());}")],
  ["Android shares actual icon", java.includes("getResources().openRawResource(R.drawable.launcher_fieldworks)")],
  ["Android shares image stream", java.includes('share.setType("image/png")') && java.includes("share.putExtra(Intent.EXTRA_STREAM, iconUri);")],
  ["Android shares caption with link", java.includes("share.putExtra(Intent.EXTRA_TEXT, FIELDWORKS_SHARE_MESSAGE);")],
  ["Android grants image read access", java.includes("share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);")],
  ["FileProvider exists", manifest.includes("androidx.core.content.FileProvider") && manifest.includes('android:grantUriPermissions="true"')],
  ["Share file fits FileProvider path", paths.includes('path="fieldworks_media/"') && java.includes('new File(getFilesDir(), "fieldworks_media")')],
  ["App icon present", fs.existsSync("app/src/main/res/drawable-nodpi/launcher_fieldworks.png")],
  ["Text-only fallback", java.includes('share.setType("text/plain")')],
  ["Web share caption", html.includes('text:"FieldWorks - Manage & Monitor your physical projects @"')],
  ["Version bumped", version.includes("buildNumber=24")],
];
for (const [name, ok] of checks) assert.ok(ok, name);
console.log("Share content, icon, URI provider, permissions, fallback and version: PASS");
