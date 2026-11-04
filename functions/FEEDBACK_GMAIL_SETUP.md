# FieldWorks in-app feedback: Gmail API backend setup

FieldWorks submits feedback entirely inside the Android app. The Android bridge POSTs to the Firebase HTTPS function `fieldWorksFeedback`; only that backend calls Gmail API. No Gmail or Google Forms UI opens on the user's phone.

**Recipient:** `growplot2026@gmail.com` (fixed in `functions/index.js`)
**Subject:** `FieldWorks Feedback` (fixed)
**Body:** feedback text, contact detail or FieldWorks user/guest ID; authenticated UID when available.
**Result:** success appears in FieldWorks only after Gmail returns a message ID.

## One-time authorization (owner/developer only)

Google requires authorization to send mail from the mailbox. Firebase service-account credentials alone cannot send mail from a consumer Gmail mailbox. Obtain **OAuth 2.0 client credentials and an offline refresh token from the mailbox that will send the feedback**, and store them in Firebase Secret Manager. Never put these credentials in the Android app, GitHub repository, client JS, screenshots, or chat messages.

1. In the Google Cloud project `fieldworks-512712`, enable **Gmail API** at `https://console.cloud.google.com/apis/library/gmail.googleapis.com?project=fieldworks-512712`.
2. Configure an OAuth consent screen, create a **Web application** OAuth client ID, and authorize `https://developers.google.com/oauthplayground` as its redirect URI. Ensure `growplot2026@gmail.com` can consent to the app. Gmail send scope is sensitive; Google's testing/publishing rules can affect refresh-token longevity.
3. In [Google OAuth 2.0 Playground](https://developers.google.com/oauthplayground/), enable **Use your own OAuth credentials** and enter the web-client ID and secret. Use the narrow scope `https://www.googleapis.com/auth/gmail.send`, grant access while signed in to `growplot2026@gmail.com`, and exchange the authorization code for an **offline refresh token**. Do not use the Playground's default OAuth client for production refresh tokens.
4. On a trusted developer computer, set the Firebase secret for the backend. The single secret must be a JSON object with exact property names:

   ```json
   {
     "client_id": "YOUR_GOOGLE_OAUTH_WEB_CLIENT_ID",
     "client_secret": "YOUR_GOOGLE_OAUTH_WEB_CLIENT_SECRET",
     "refresh_token": "YOUR_GMAIL_SEND_REFRESH_TOKEN"
   }
   ```

   Store this value using the Firebase CLI `firebase functions:secrets:set FIELDWORKS_GMAIL_OAUTH --project fieldworks-512712`. The CLI securely prompts for the value; do **not** commit the JSON to a file or repository.

5. Deploy the backend from the repository root with `firebase deploy --only functions:fieldWorksFeedback --project fieldworks-512712`. Deploying code to GitHub by itself **does not** deploy this function.
6. Install an APK built from the new FieldWorks source. Submit sample feedback inside the app and verify that `growplot2026@gmail.com` receives the email with subject **FieldWorks Feedback**. Check both a signed-in user and a guest, and verify failure reporting when the server cannot send. Never claim live delivery until the actual Gmail API call is verified.

## Backend guards

- The recipient and subject are not supplied by the Android client, so the endpoint is not an arbitrary-recipient email relay.
- Authentication tokens, when available, are checked by Firebase Admin; guests are permitted but limited.
- IP-based daily and cooldown quotas, plus an app-wide daily quota, apply before calling Gmail.
- The endpoint returns JSON `{success, message}`; the Android UI only clears the feedback after HTTP 2xx with `success: true`.
- If the Gmail credential has not been configured, returns HTTP 503 and displays an error in the FieldWorks interface; **this must not be described as a completed live integration**.

## References

- [Google Gmail API — send mail](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/send)
- [Firebase functions secret parameters](https://firebase.google.com/docs/functions/config-env)
