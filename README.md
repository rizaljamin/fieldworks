<p align="center"><img src="assets/fieldworks-logo.svg" alt="FieldWorks" width="240"></p>

# FieldWorks Android

FieldWorks is an Android field journal for managing projects and recording progress with notes, photos, videos, and voice notes.

## Current source version

**V0.4.00 Build 06** on the `main` branch (as of 4 November 2026), recorded in [`fieldworks-version.properties`](fieldworks-version.properties). The 30 October 2026 commits rolled back the UI to a pre-template Build 06 baseline after a startup regression, then restored the version properties. This is the **source baseline**, not a claim that the latest GitHub Actions build passed or that this version was uploaded to Google Play.

**Historical build and distribution information:** V0.3.00 Build 14 was previously documented as a successful GitHub Actions run ([run #186](https://github.com/rizaljamin/fieldworks/actions/runs/40707503354)); V0.3.00 Build 08 was previously documented as the Google Play closed-test build. These are historical statements, not freshly verified current distribution statuses.

Android configuration: `compileSdk 36`, `targetSdk 36`, `minSdk 24`. The Gradle build takes Android version name/code from workflow-provided properties; the FieldWorks release/build label is maintained in `fieldworks-version.properties`.

**Stability note:** The Build 06 rollback followed a startup regression. Device compatibility, account switching, template entitlement rendering, and restored demo-project behavior must be regression-tested before any new release is described as stable.

## Features and functionality

FieldWorks is a mobile project journal aimed at fieldwork, renovations, events, small-business projects and related activities. The repository documents the following feature areas; individual flows still require verification on the current rollback baseline:

- **Projects and templates:** Create projects from available project types/templates, manage project cards and project-specific milestones, and track dates and progress. Template subscription gating has been under development; the rollback means newer template-access behavior must not be assumed present or working.
- **Project journal and albums:** Capture or attach photos, videos, written notes and voice notes; organize media and notes by project and stage, with comments and stage-based albums.
- **Milestones and project organization:** Track current stages, start/end dates, pinned or favorite projects, and stage-specific records. Verify the exact available controls in Build 06 before publication.
- **Issues and reminders:** Record issues, status, severity, timestamps and target dates. Issue aging indicators are intended to distinguish items over 15 and 30 days.
- **Budget and expenses:** Track project budgets and stage-related spending, with categories such as manpower, materials and logistics and supporting attachments where supported.
- **Reports:** Generate project progress reports drawing on stages, issues, finance and selected media. Photo selection, full-report click behavior and report completeness are regression-test items, not verified features of this baseline.
- **Archive and restore:** Archive project data and restore from a selected file/path where supported. Confirm archive ownership checks and preservation of existing data before using on important projects.
- **Accounts:** Guest mode and Firebase-backed email/password or Google sign-in are documented. Project data uses local device storage (IndexedDB); cross-device project synchronization is not guaranteed. Account switching must not expose a previous user's project cards.
- **Subscriptions:** Google Play Billing and a Firebase account-binding function have been developed. Subscription verification and entitlement changes require end-to-end testing; do not treat these as production-validated based on repository presence alone.

### Known verification priorities

1. Launch on Samsung A50 and newer Android devices without a startup hang.
2. Preserve demo and existing projects through app updates and account changes.
3. Clear or reload account-scoped project cards and template entitlements after logout/login.
4. Verify enabled/disabled templates and subscription navigation.
5. Verify album media, report photographs, archive/restore and budget records.
6. Run dependency, flow and regression checks before modifying the baseline.

## Build with GitHub Actions

Push changes under `app/`, update the root Gradle files or `fieldworks-version.properties`, or start **Build FieldWorks APK and AAB** from the repository’s Actions tab. Each successful run uploads the signed APK, signed AAB, and a supporting bundle with app assets and the build memo. Artifacts are retained for 30 days.

The workflow uses the repository signing secrets to restore the existing release keystore in the temporary runner directory. Keep that keystore for updates that must install over previously signed builds; do not commit it to the repository.
