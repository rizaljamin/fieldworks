<p align="center"><img src="assets/fieldworks-logo.svg" alt="FieldWorks" width="240"></p>

# FieldWorks Android

FieldWorks is an Android field journal for managing projects and recording progress with notes, photos, videos, and voice notes.

## Version

**Latest successful build: V0.3.00 Build 5 (GitHub Actions run #177).** Run #177 completed successfully and produced the signed APK, signed AAB, and supporting files. [View the run and download its artifacts](https://github.com/rizaljamin/fieldworks/actions/runs/40631585023).

The release/build label comes from `fieldworks-version.properties`. Android `versionCode` uses the GitHub Actions run number, so this build has versionCode 177.

## Features

- **Projects and templates:** Create blank projects or start from a template. Milestones follow the selected project type; Farms & Agriculture includes land assessment, preparation, planting, crop care, and harvest stages.
- **Project journal:** Add notes, photos, videos, and voice notes. Each project card shows a preview of its latest saved note. Capture media with the camera or import it from device folders and Google Drive through Android’s file picker.
- **Project progress:** Track template-specific stages, dates, follow-ups with target accomplishment dates, budget notes, albums, and progress photos. Issue tracking and reminders appear at the top of project details for quick access.
- **Issue log:** Number issues oldest first, show days open, and flag active issues yellow from 15 days and red after 30 days. Track Low, Medium, High, or Critical severity and Open, In progress, or Resolved status.
- **Archive:** Open Archive from the sidebar directly above My Account, or from the mobile bottom navigation. Archive and restore projects, or create downloadable project backups.
- **Google Play subscriptions:** Student and Personal six-month plans use the configured Google Play product IDs and Android BillingClient purchase and restore flows. When testing outside Google Play, the app explains that purchases and restores may fail. Business is marked as coming soon.
- **Accounts and local storage:** Use guest access or sign in with Firebase email/password or Google. Project records, reminders, issues, and media are stored on the device in IndexedDB and restored when the app starts. Data persists across launches and app updates, but does not sync across devices. Clearing app storage or uninstalling removes local data.

## Build with GitHub Actions

Push changes under `app/`, update the root Gradle files or `fieldworks-version.properties`, or start **Build FieldWorks APK and AAB** from the repository’s Actions tab. Each successful run uploads the signed APK, signed AAB, and a supporting bundle with app assets and the build memo. Artifacts are retained for 30 days.

The workflow uses the repository signing secrets to restore the existing release keystore in the temporary runner directory. Keep that keystore for updates that must install over previously signed builds; do not commit it to the repository.
