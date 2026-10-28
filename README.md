<p align="center"><img src="assets/fieldworks-logo.svg" alt="FieldWorks" width="240"></p>

# FieldWorks Android

FieldWorks is an Android field journal for managing projects and recording progress with notes, photos, videos, and voice notes.

## Version

**Latest successful build: V0.3.00 Build 01 (GitHub Actions run #164).** The run completed successfully and produced the signed APK, signed AAB, and supporting files. [View the run and download its artifacts](https://github.com/rizaljamin/fieldworks/actions/runs/40620456760).

The release and build label come from `fieldworks-version.properties`. Android `versionCode` uses the GitHub Actions run number; Build 01 has versionCode 164.

## Features

- **Projects and templates:** Create a blank project or start from a template, define custom project types, and keep project navigation separate from the template library. Signed-in project lists are scoped to the current account.
- **Favourites:** Pin favourite projects for quick access from the workspace.
- **Project progress:** Track stages, select the current stage, set stage start and end dates, and add stage notes and follow-ups.
- **Archive and restore:** Archive a project from its detail view. Archived projects leave the active lists and can be restored from the Archived projects section.
- **Issue log:** Record project issues with Low, Medium, High, or Critical severity and track each as Open, In progress, or Resolved.
- **Project journal:** Add text notes, photos, videos, and voice notes. Capture photos or video with the camera, or import media from device folders and Google Drive through Android’s file picker.
- **Albums and progress views:** Group media by stage and upload batch, add comments, view upload timestamps, choose album covers, and browse photo collages or flip-through albums.
- **Accounts and access:** Use guest mode or sign in with Firebase email/password or Google. The account flow includes password reset. The subscription screen shows trial and plan status; Business is marked as coming soon.
- **Local storage:** Project records, reminders, issues, and captured media are stored in IndexedDB on the device. They persist across app launches and updates, but do not sync between devices. Clearing app storage or uninstalling removes the local data.

## Build with GitHub Actions

Push changes under `app/`, update the root Gradle files or `fieldworks-version.properties`, or start **Build FieldWorks APK and AAB** from the repository’s Actions tab. Each successful run uploads the signed APK, signed AAB, and a supporting bundle with app assets and the build memo. Artifacts are retained for 30 days.

The workflow uses the repository signing secrets to restore the existing release keystore in the temporary runner directory. Keep that keystore for updates that must install over previously signed builds; do not commit it to the repository.
