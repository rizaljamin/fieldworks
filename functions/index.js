const { onRequest } = require("firebase-functions/v2/https");
const { defineJsonSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const { google } = require("googleapis");
const { createHash } = require("crypto");

admin.initializeApp({ projectId: "fieldworks-512712" });
const db = admin.firestore();
const feedbackGmailOAuth = defineJsonSecret("FIELDWORKS_GMAIL_OAUTH");

const PACKAGE_NAME = "com.fieldworks.app";
const PRODUCT_IDS = new Set(["fw2026", "fw2026_stu"]);
const ACTIVE_STATES = new Set([
  "SUBSCRIPTION_STATE_ACTIVE",
  "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
  "SUBSCRIPTION_STATE_CANCELED",
]);

let publisherPromise;
function androidPublisher() {
  if (!publisherPromise) {
    publisherPromise = (async () => {
      const auth = new google.auth.GoogleAuth({
        scopes: ["https://www.googleapis.com/auth/androidpublisher"],
      });
      const client = await auth.getClient();
      return google.androidpublisher({ version: "v3", auth: client });
    })();
  }
  return publisherPromise;
}

function hashToken(token) {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

function obfuscatedAccountId(uid) {
  return createHash("sha256").update(uid, "utf8").digest("hex");
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

async function verifyWithGoogle(purchaseToken, productId, uid) {
  let response;
  try {
    const api = await androidPublisher();
    response = await api.purchases.subscriptionsv2.get({
      packageName: PACKAGE_NAME,
      token: purchaseToken,
    });
  } catch (error) {
    if (Number(error.code) === 404) {
      return { active: false, productId, expiryTime: null };
    }
    throw error;
  }

  const purchase = response.data || {};
  const externalId = purchase.externalAccountIdentifiers
    && purchase.externalAccountIdentifiers.obfuscatedExternalAccountId;
  if (externalId && externalId !== obfuscatedAccountId(uid)) {
    throw httpError(409, "This Google Play subscription is already associated with a different FieldWorks account.");
  }

  const lines = (purchase.lineItems || []).filter((line) => line.productId === productId);
  const expiryTime = lines
    .map((line) => line.expiryTime || "")
    .filter(Boolean)
    .sort()
    .pop() || null;
  const expiryIsFuture = !!expiryTime && Date.parse(expiryTime) > Date.now();
  const active = ACTIVE_STATES.has(purchase.subscriptionState) && expiryIsFuture;
  return { active, productId, expiryTime };
}

async function bindPurchase(uid, purchaseToken, productId) {
  const tokenHash = hashToken(purchaseToken);
  const ownerRef = db.collection("fieldWorksPurchaseOwners").doc(tokenHash);
  const subscriptionRef = db.collection("fieldWorksEntitlements")
    .doc(uid).collection("subscriptions").doc(tokenHash);

  await db.runTransaction(async (transaction) => {
    const owner = await transaction.get(ownerRef);
    if (owner.exists && owner.get("uid") !== uid) {
      throw httpError(409, "This Google Play subscription is already associated with a different FieldWorks account.");
    }
    transaction.set(ownerRef, {
      uid,
      createdAt: owner.exists ? owner.get("createdAt") : admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
    transaction.set(subscriptionRef, {
      purchaseToken,
      productId,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
  });

  return { subscriptionRef };
}

async function refreshUserEntitlement(uid) {
  const subscriptions = await db.collection("fieldWorksEntitlements")
    .doc(uid).collection("subscriptions").get();
  let activeProductId = "";
  let latestExpiry = null;

  for (const document of subscriptions.docs) {
    const record = document.data();
    if (!record.purchaseToken || !PRODUCT_IDS.has(record.productId)) continue;
    try {
      const result = await verifyWithGoogle(record.purchaseToken, record.productId, uid);
      await document.ref.set({
        active: result.active,
        expiryTime: result.expiryTime,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      }, { merge: true });
      if (result.active) {
        activeProductId = result.productId;
        if (!latestExpiry || (result.expiryTime && result.expiryTime > latestExpiry)) {
          latestExpiry = result.expiryTime;
        }
      }
    } catch (error) {
      if (error.status === 409) {
        await document.ref.set({
          active: false,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        }, { merge: true });
        continue;
      }
      throw error;
    }
  }

  return { active: !!activeProductId, productId: activeProductId, expiryTime: latestExpiry };
}

exports.fieldWorksSubscription = onRequest(
  { region: "asia-southeast1", maxInstances: 5, timeoutSeconds: 60 },
  async (req, res) => {
    res.set("Cache-Control", "no-store");
    if (req.method !== "POST") {
      res.status(405).json({ message: "POST required." });
      return;
    }

    try {
      const authorization = req.get("Authorization") || "";
      const match = authorization.match(/^Bearer (.+)$/i);
      if (!match) throw httpError(401, "Sign in to FieldWorks to check your subscription.");
      const decoded = await admin.auth().verifyIdToken(match[1]);
      const uid = decoded.uid;
      const action = req.body && req.body.action;

      if (action === "status") {
        const entitlement = await refreshUserEntitlement(uid);
        res.status(200).json({ uid, ...entitlement });
        return;
      }

      if (action === "verify") {
        const purchaseToken = String((req.body && req.body.purchaseToken) || "");
        const productId = String((req.body && req.body.productId) || "");
        if (!purchaseToken || purchaseToken.length > 8192 || !PRODUCT_IDS.has(productId)) {
          throw httpError(400, "The Google Play purchase details are invalid.");
        }

        const verified = await verifyWithGoogle(purchaseToken, productId, uid);
        const { subscriptionRef } = await bindPurchase(uid, purchaseToken, productId);
        await subscriptionRef.set({
          active: verified.active,
          expiryTime: verified.expiryTime,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        }, { merge: true });
        res.status(200).json({ uid, ...verified });
        return;
      }

      throw httpError(400, "Unknown subscription request.");
    } catch (error) {
      const status = Number(error.status) || 500;
      const message = status >= 500
        ? "Google Play subscription verification is temporarily unavailable."
        : error.message;
      console.error("FieldWorks subscription verification failed:", status, error.code || error.status || "unknown");
      res.status(status).json({ message });
    }
  }
);


// FieldWorks Feedback is a fixed-destination email endpoint, not an email relay.
// A private OAuth refresh token grants the backend gmail.send only; it is NEVER
// shipped in the APK or returned to users. Guest submissions are rate-limited.
exports.fieldWorksFeedback = onRequest(
  {
    region: "asia-southeast1",
    maxInstances: 3,
    timeoutSeconds: 45,
    secrets: [feedbackGmailOAuth],
  },
  async (req, res) => {
    res.set("Cache-Control", "no-store");
    if (req.method !== "POST") {
      res.status(405).json({ success: false, message: "POST required." });
      return;
    }
    if (!String(req.get("Content-Type") || "").toLowerCase().includes("application/json")) {
      res.status(415).json({ success: false, message: "JSON required." });
      return;
    }

    const feedback = typeof req.body?.feedback === "string" ? req.body.feedback.trim() : "";
    const contact = typeof req.body?.contact === "string" ? req.body.contact.trim() : "";
    if (!feedback || !contact || feedback.length > 3000 || contact.length > 250) {
      res.status(400).json({ success: false, message: "Please enter feedback and a valid contact or FieldWorks User ID." });
      return;
    }

    let verifiedUid = "";
    try {
      const authHeader = String(req.get("Authorization") || "");
      if (authHeader) {
        const match = authHeader.match(/^Bearer (.+)$/i);
        if (!match) throw httpError(401, "Invalid FieldWorks account authorization.");
        const decoded = await admin.auth().verifyIdToken(match[1]);
        verifiedUid = decoded.uid;
      }
    } catch (_) {
      res.status(401).json({ success: false, message: "Your FieldWorks sign-in needs refreshing. Please sign in again." });
      return;
    }

    try {
      const credentials = feedbackGmailOAuth.value();
      if (!credentials || !credentials.client_id || !credentials.client_secret || !credentials.refresh_token) {
        throw httpError(503, "FieldWorks feedback email service has not been activated.");
      }

      // Prevent unauthenticated callers from using Gmail as an open relay.
      // The destination and subject are constants; both individual IP and
      // app-wide daily quotas are applied transactionally before Gmail sends.
      const day = new Date().toISOString().slice(0, 10);
      const fingerprint = createHash("sha256")
        .update(String(req.ip || req.socket?.remoteAddress || "unknown"), "utf8").digest("hex");
      const limitRef = db.collection("fieldWorksFeedbackRateLimits").doc(fingerprint);
      const globalRef = db.collection("fieldWorksFeedbackRateLimits").doc("daily-" + day);
      const now = Date.now();
      await db.runTransaction(async (tx) => {
        const [individual, global] = await Promise.all([tx.get(limitRef), tx.get(globalRef)]);
        const previous = individual.exists && individual.get("day") === day ? individual.data() : {};
        const today = global.exists ? global.data() : {};
        if (Number(previous.lastAttemptAt || 0) + 45000 > now) {
          throw httpError(429, "Feedback was just submitted. Please retry after a short interval.");
        }
        if (Number(previous.count || 0) >= 20 || Number(today.count || 0) >= 250) {
          throw httpError(429, "The feedback submission limit has been reached for today. Please try tomorrow.");
        }
        tx.set(limitRef, {
          day, count: Number(previous.count || 0) + 1, lastAttemptAt: now,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        tx.set(globalRef, {
          day, count: Number(today.count || 0) + 1,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      });

      const oauth = new google.auth.OAuth2(credentials.client_id, credentials.client_secret);
      oauth.setCredentials({ refresh_token: credentials.refresh_token });
      const gmail = google.gmail({ version: "v1", auth: oauth });
      // User-entered content is in the body only, never email headers.
      const body = [
        "Feedback:", feedback, "",
        "Contact detail / FieldWorks User ID:", contact,
        ...(verifiedUid ? ["", "Verified FieldWorks UID:", verifiedUid] : []),
        "", "Submitted via FieldWorks Android app.",
        "Received at: " + new Date().toISOString(),
      ].join("\n");
      const encodedBody = Buffer.from(body, "utf8").toString("base64").match(/.{1,76}/g)?.join("\r\n") || "";
      const rawMessage = [
        "From: FieldWorks Feedback <growplot2026@gmail.com>",
        "To: growplot2026@gmail.com",
        "Subject: FieldWorks Feedback",
        "MIME-Version: 1.0",
        "Content-Type: text/plain; charset=UTF-8",
        "Content-Transfer-Encoding: base64",
        "",
        encodedBody,
      ].join("\r\n");
      const response = await gmail.users.messages.send({
        userId: "me", requestBody: { raw: Buffer.from(rawMessage, "utf8").toString("base64url") },
      });
      if (!response.data?.id) throw httpError(502, "Gmail did not confirm acceptance of the feedback.");
      res.status(200).json({ success: true, message: "FieldWorks feedback sent successfully." });
    } catch (error) {
      const status = [400, 401, 403, 429, 502, 503].includes(Number(error.status))
        ? Number(error.status) : 502;
      const publicMessage = error.status
        ? error.message
        : "Feedback could not be sent by Gmail. Please try again or contact FieldWorks support.";
      console.error("FieldWorks feedback send error", status, error.code || "unknown");
      res.status(status).json({ success: false, message: publicMessage });
    }
  }
);
