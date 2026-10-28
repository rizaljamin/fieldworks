<p align="center"><img src="assets/fieldworks-logo.svg" alt="FieldWorks" width="240"></p>

# FieldWorks Android

FieldWorks is an Android field journal for managing projects and recording progress with notes, photos, videos, and voice notes.

## Version

**Latest successful build: V0.3.00 Build 12 (GitHub Actions run #184).** Run #184 completed successfully and produced the signed APK, signed AAB, and supporting files. [View the run and download its artifacts](https://github.com/rizaljamin/fieldworks/actions/runs/40684029361).

The release/build label comes from `fieldworks-version.properties`. Android `versionCode` uses the GitHub Actions run number, so Build 12 has versionCode 184. Build 13 is the next source build and restores the direct current-stage picker.

**Google Play status:** Closed testing currently uses **V0.3.00 Build 8**. Build 12 is a GitHub Actions artifact and has not been uploaded to Google Play; Build 13 is pending its Actions run.

The account-binding Cloud Function is deployed to `asia-southeast1` on Node.js 22. Verify the account-binding flow with a Play test purchase before production distribution; setup details are in [the account-binding guide](docs/subscription-account-binding.md).

## Features

- **Projects and templates:** Create blank projects or start from a template. Keep a Mini Renovation sample project available separately for each local account. Project cards summarize issue and reminder counts and spending, with shortcuts to each detail section. Milestones follow the selected project type; Farms & Agriculture includes land assessment, preparation, planting, crop care, and harvest stages.
- **Project journal:** Add notes, photos, videos, and voice notes. Each project card shows a preview of its latest saved note. Capture media with the camera or import it from device folders and Google Drive through Android’s file picker.
- **Project progress:** Track template-specific stages and dates, set the current stage directly from its picker, move entire photo/video albums between stages, and manage follow-ups with target accomplishment dates. Record expenses by stage, category (Manpower, Materials, Logistics, Equipment, Utilities, Permits & fees, or Other), description, amount, and date; the project budget shows itemized costs alongside any older unitemized spending. Issue tracking and reminders appear at the top of project details for quick access.
- **Issue log:** Number issues oldest first, show days open, and flag active issues yellow from 15 days and red after 30 days. Track Low, Medium, High, or Critical severity and Open, In progress, or Resolved status.
- **Archive:** Open Archive from the sidebar directly above My Account, or from the mobile bottom navigation. Archive and restore projects, or create downloadable project backups.
- **Google Play subscriptions:** Student and Personal six-month plans use Google Play Billing. The backend verifies purchase tokens and binds each token to one Firebase account, preventing another FieldWorks account from claiming the same subscription. The Cloud Function has been deployed to asia-southeast1. Confirm the Play Developer API service-account permissions and subscription verification with a Play test purchase before distributing a build that includes account binding. Business is marked as coming soon.
- **Accounts and local storage:** Use guest access or sign in with Firebase email/password or Google. Project records, reminders, issues, and media are stored on the device in IndexedDB and restored when the app starts. Data persists across launches and app updates, but does not sync across devices. Clearing app storage or uninstalling removes local data.

## Build with GitHub Actions

Push changes under `app/`, update the root Gradle files or `fieldworks-version.properties`, or start **Build FieldWorks APK and AAB** from the repository’s Actions tab. Each successful run uploads the signed APK, signed AAB, and a supporting bundle with app assets and the build memo. Artifacts are retained for 30 days.

The workflow uses the repository signing secrets to restore the existing release keystore in the temporary runner directory. Keep that keystore for updates that must install over previously signed builds; do not commit it to the repository.
