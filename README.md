<p align="center"><img src="assets/fieldworks-logo.svg" alt="FieldWorks" width="240"></p>

# FieldWorks Android

FieldWorks is an Android fieldbook app for documenting hands-on projects with photos, videos, text, and voice notes. The current development build includes guest entry, Firebase email account creation, sign-in and password reset, project creation, project stages, a pinned project, project albums, and photo/video capture from the camera, device folders, or Google Drive through Android’s file picker.

## Build an APK with GitHub Actions

Push changes under app/, update the root Gradle files, or start **Build FieldWorks APK** from the repository's Actions tab. The workflow builds a signed release APK and saves it as a 30-day artifact on the workflow run.

The workflow uses the existing repository secrets: KEYSTORE_BASE64, STORE_PASSWORD, and KEY_PASSWORD. It reads the signing alias from the keystore itself, restores the existing keystore into the temporary runner directory, and never writes it to the repository. The KEY_ALIAS secret can remain set but is not used. Do not replace or regenerate the keystore if you need updates to install over earlier signed builds.

## Version numbering

Every generated APK uses the filename format `FieldWorks VX.X.YY Build ZZ.apk` (for example, `FieldWorks V0.1.00 Build 19.apk`). The build number increments on each workflow run. After Build 30, the version advances to V0.2.00 Build 01. Android versionCode uses the workflow run number.

## Current development storage

Project records, reminders, and captured media are saved in IndexedDB on the device and persist across app launches and app updates. Data is currently device-local and does not sync to a cloud account. Clearing app storage or uninstalling the app removes the saved fieldbook.

Email/password authentication uses Firebase Authentication. Enable Email/Password in the Firebase console. Google sign-in is connected through Android Credential Manager and Firebase Authentication. Signed-in state is shown in the workspace and restored on later launches; users can sign out from the sidebar. Facebook sign-in still requires Meta app configuration and a Facebook SDK flow. Authentication identifies the user; project records and media remain stored locally on each device and do not sync between devices.
