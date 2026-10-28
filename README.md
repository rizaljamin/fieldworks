<p align="center"><img src="assets/fieldworks-logo.svg" alt="FieldWorks" width="240"></p>

# FieldWorks Android

FieldWorks is an Android field journal for managing projects and recording progress with notes, photos, videos, and voice notes.

## Version

**Latest closed-testing build confirmed in GitHub Actions: V0.2.02 Build 33.**

The Actions artifact screenshot for Build 33 contains the APK, AAB, and supporting files. Use the latest successful Actions run and its artifacts to identify the build that was actually produced; commit titles alone may not match the latest uploaded test build.

The version metadata currently committed on `main` is `V0.2.02 Build 14` in `fieldworks-version.properties`. Sync that file with the intended release/build before starting another workflow run. The workflow uses that file for the displayed version and the GitHub Actions run number for Android `versionCode`.

## Features

The committed app includes:

- **Projects and templates:** Create a blank project or start from a template, define custom project types, and keep project navigation separate from the template library. Signed-in project lists are scoped to the current account.
- **Favourites:** Pin favourite projects for quick access from the workspace.
- **Project progress:** Track project stages, select the current stage, set stage start and end dates with calendar controls, and add stage notes and follow-ups.
- **Issue log:** Record issues within a project.
- **Project journal:** Add text notes, photos, videos, and voice notes. Capture photos or video with the camera, or import media from device folders and Google Drive through Android’s file picker.
- **Albums and progress views:** Group media by stage and upload batch, add comments, view upload timestamps, choose album covers, and browse photo collages or flip-through albums.
- **Accounts and access:** Use guest mode or sign in with Firebase email/password or Google. The account flow includes password reset. The subscription screen shows trial and plan status; Business is marked as coming soon.
- **Local storage:** Project records, reminders, and captured media are stored in IndexedDB on the device. They persist across app launches and updates, but do not sync between devices. Clearing app storage or uninstalling removes the local data.

## Build with GitHub Actions

Push changes under `app/`, update the root Gradle files or `fieldworks-version.properties`, or start **Build FieldWorks APK** from the repository’s Actions tab. Check the run’s artifacts for the signed APK and any additional outputs. Artifacts are retained for 30 days.

The workflow uses the repository signing secrets to restore the existing release keystore in the temporary runner directory. Keep that keystore for updates that must install over previously signed builds; do not commit it to the repository.
