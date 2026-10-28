# Subscription account binding

FieldWorks now checks subscription ownership on a trusted Firebase backend. A Google Play token is verified with the Android Publisher API, then bound to one Firebase Authentication UID. A second FieldWorks account cannot claim the same token. The app also checks the signed-in UID against the obfuscated account ID sent to Google Play when the subscription was purchased.

## One-time Google and Firebase setup

1. In Google Cloud project `fieldworks-512712`, enable the **Google Play Android Developer API**.
2. Give the Cloud Functions runtime service account access to the FieldWorks app in Play Console with permission to view subscription and order data. The function uses Application Default Credentials; do not put a service-account key in the app or repository.
3. Enable Firebase Authentication and Cloud Firestore for this project, and ensure the project is on the Blaze plan so Cloud Functions can be deployed.
4. From this repository, run `npm install --prefix functions`, then `firebase deploy --only functions:fieldWorksSubscription,firestore:rules`.
5. Confirm that the function is available at `https://asia-southeast1-fieldworks-512712.cloudfunctions.net/fieldWorksSubscription` before distributing an APK/AAB that uses server-side subscription checks.

## Ownership and legacy purchases

The app passes a SHA-256 obfuscated FieldWorks UID to Google Play at purchase time. The backend checks that value when Google Play returns it. For older active purchases with no obfuscated account ID, the first FieldWorks account that successfully verifies the token claims it; the token is stored server-side and cannot be claimed by another account afterward. Existing subscriptions should be restored while signed in to the FieldWorks account that should own them.

Firestore rules deny direct client reads and writes to the entitlement and purchase-owner records. Only the Admin SDK in the Cloud Function can access them.

The app fails closed when it cannot verify an entitlement. Do not publish a build using these checks until the function and Play API permissions are configured.
