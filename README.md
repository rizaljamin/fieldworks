<p align="center"><img src="assets/fieldworks-logo.svg" alt="FieldWorks" width="240"></p>

# FieldWorks Android

FieldWorks is an Android WebView shell around the FieldWorks fieldbook prototype. It includes guest entry, a demo username/password form, project creation, project stages, and photo, video, text, and voice journal capture.

## Build an APK with GitHub Actions

Push changes under app/, update the root Gradle files, or start **Build FieldWorks APK** from the repository's Actions tab. The workflow builds a signed release APK and saves it as a 30-day artifact on the workflow run.

The workflow uses the existing repository secrets: KEYSTORE_BASE64, STORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD. It restores the existing keystore into the temporary runner directory and never writes it to the repository. Do not replace or regenerate that keystore if you need updates to install over earlier signed builds.

## Version numbering

The workflow starts at 0.1 build 01, then increments the build number on each workflow run. After build 30 it advances to 0.2 build 01. Android versionCode uses the workflow run number.

## Prototype limitations

The sign-in form is a visual demo and accepts any non-empty username/password; Google and Facebook buttons are placeholders. Project records are demo data held in the embedded page, not a synced production account.
