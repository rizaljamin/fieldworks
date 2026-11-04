# FieldWorks in-app Feedback → Firebase Firestore

This replaces the previous Google Forms / Gmail API feedback proposals.

## Intended data

On Submit, FieldWorks sends a JSON POST to the HTTPS Cloud Function `fieldWorksFeedback` in Firebase project `fieldworks-512712`, region `asia-southeast1`.

The function writes to the Cloud Firestore **top-level collection** `feedback`, with numeric string document IDs starting from `1`:

```text
feedback/
  1/
    timestamp: <Firestore server timestamp>
    userid: "Firebase Auth UID or guest-..."
    feedback_message: "User's message"
  2/
    timestamp: ...
    userid: ...
    feedback_message: ...
```

The counter is stored separately at `fieldWorksCounters/feedback` with `lastId` = latest number. Transactions guarantee unique sequential IDs on simultaneous submissions. A request receipt in `fieldWorksFeedbackRequests` prevents duplicate documents if a phone times out and retries the same request. Basic IP/app rate-limit documents live in `fieldWorksFeedbackRateLimits`.

A signed-in user's UID is **verified by Firebase Authentication**. A guest is identified by a persistent locally generated `guest-...` ID. For privacy, the feedback collection contains only the user's requested fields.

The user stays in the FieldWorks Feedback card; the status indicates “Feedback #N saved successfully” only after the function's successful Firestore transaction.

## Required deployment

**Committing GitHub code or building the APK does not deploy this Firebase function or create live Firestore documents.** Cloud Firestore creates the new collection automatically when the first document is written by the deployed function.

1. Confirm that Cloud Firestore has been provisioned for Firebase project [fieldworks-512712](https://console.firebase.google.com/project/fieldworks-512712/firestore). Ensure that this project supports Cloud Functions and has a suitable billing plan.
2. On a trusted developer machine with Firebase CLI access and suitable permissions, run the following from the FieldWorks GitHub repository root:

   ```sh
   firebase login
   firebase use fieldworks-512712
   firebase deploy --only functions:fieldWorksFeedback,firestore:rules --project fieldworks-512712
   ```

3. The Cloud Function must be invokable by the Android app including **Guest** users (HTTPS invocation is public but function validates the body, verifies signed-in user tokens, checks guest IDs, and rate limits submissions).
4. Build and install the current FieldWorks Android APK (newer than Build 18) from the GitHub Actions signed APK workflow. This APK sends feedback via an Android background request; no external email application or OAuth Gmail secret is needed.
5. In the app, submit a short test as guest and a second test while signed in. Check the Firebase Console **feedback** collection and verify document IDs `1`, `2`, etc., fields `timestamp`, `userid`, `feedback_message`. A previously existing numeric counter is respected; IDs may not start at `1` if data already exists.
6. Test network failure and repeated Submit: the UI must preserve feedback on failure, and retrying the same message uses a stable request ID to avoid duplicate records.

## Important

The previous Gmail OAuth secret `FIELDWORKS_GMAIL_OAUTH` is no longer used by the latest Firestore feedback handler. Do not set up Gmail API for this feature.

The Firestore rules explicitly deny direct reads/writes to `feedback` and its counter/receipt/limit collections from client devices. The trusted Cloud Function uses Firebase Admin SDK and bypasses client rules. Reading feedback should be done by an authorized project administrator from the Firebase Console or a future authenticated admin interface.
