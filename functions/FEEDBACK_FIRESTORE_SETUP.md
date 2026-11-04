# FieldWorks Feedback → Firestore fieldWorksFeedback

## Collection selected by the user

The user has already created a top-level Firestore collection named **fieldWorksFeedback** in Firebase project `fieldworks-512712`. The initially created random-ID document is empty. It is not a submitted feedback entry and is not used for sequential IDs.

When a real user taps **Submit** in the FieldWorks feedback card, the Android app calls its native `FieldWorksCommunity.submitFeedback(message, userid, requestId)` bridge. Native Android obtains the current Firebase Auth ID token when signed in (guest IDs are supported), then sends a JSON POST to the HTTPS Cloud Function `fieldWorksFeedback` in `asia-southeast1`. The trusted function uses a Firestore transaction to write to:

```text
fieldWorksFeedback/
  1/
    timestamp: <Firestore server timestamp>
    userid: "Firebase Auth UID or guest-..."
    feedback_message: "Actual submitted user text"
  2/
    timestamp: ...
    userid: ...
    feedback_message: ...
```

The first **actual** submission writes document ID `1` if no numeric feedback records exist. No fake/test feedback is pre-inserted. Subsequent submissions receive IDs `2`, `3`, etc. The existing empty random-ID document may remain: it does not consume a sequential ID.

The counter is separate: `fieldWorksCounters/fieldWorksFeedback` (`lastId`). The counter and feedback record are created atomically in one Firestore transaction. A request receipt in `fieldWorksFeedbackRequests` avoids duplicates on retries; IP and daily rate-limit records live in `fieldWorksFeedbackRateLimits`. The feedback document stores **only** `timestamp`, `userid`, and `feedback_message`.

A signed-in UID is verified against Firebase Authentication, not trusted from the request body. Guest IDs are persistent locally generated identifiers, not verified identities. The app confirms "Feedback #N saved successfully" only after the server returns a committed document ID; otherwise the message stays in the form.

## Deployment still required

**GitHub commits and APK builds do not deploy Cloud Functions or write to Firestore.** Before claiming the feature works live, an authorized operator must deploy the revised function and rules:

```sh
firebase login
firebase deploy --only functions:fieldWorksFeedback,firestore:rules --project fieldworks-512712
```

The function must accept HTTPS calls from app guests (`invoker: "public"`), and the Firebase project must support Cloud Functions. Never place a Firebase Admin service-account private key in this public GitHub repository or the Android APK.

## Acceptance verification

1. Confirm the deployed Cloud Function `fieldWorksFeedback` exists in project `fieldworks-512712` (region `asia-southeast1`).
2. Install FieldWorks V0.4.01 Build 22 or newer.
3. As a guest, enter meaningful feedback and press Submit. Confirm that the UI reports `Feedback #1 saved successfully` only after the backend has saved it.
4. Inspect `fieldWorksFeedback/1` and verify exactly three fields: a server `timestamp`, the app guest `userid`, and the submitted `feedback_message`.
5. Repeat as a signed-in user. Confirm `fieldWorksFeedback/2` with the authenticated Firebase UID.
6. Retry a submission after simulated network timeout: the same request ID must not create a duplicate document; a new submission must receive a new ID. On genuine failure the form must not report success or clear the message.

Firestore Security Rules deny client access to this collection and counter/receipt/rate-limit records. The Cloud Function's Firebase Admin SDK performs the trusted writes. The previous Google Forms/Gmail feedback flow is not used for this feature.
