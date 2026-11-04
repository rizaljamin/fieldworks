const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const { google } = require("googleapis");
const { createHash } = require("crypto");

admin.initializeApp({ projectId: "fieldworks-512712" });
const db = admin.firestore();

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


// Feedback is stored in Cloud Firestore, without email or Google Forms.
// The counter and feedback document are written in one Firestore transaction.
// Every persisted feedback document has ONLY the three requested fields.
exports.fieldWorksFeedback = onRequest(
  { region: "asia-southeast1", maxInstances: 5, timeoutSeconds: 45, invoker: "public" },
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
    const message = typeof req.body?.feedback === "string" ? req.body.feedback.trim() : "";
    const proposedUid = typeof req.body?.userid === "string" ? req.body.userid.trim() : "";
    const requestId = typeof req.body?.request_id === "string" ? req.body.request_id.trim() : "";
    if (!message || message.length > 3000 || proposedUid.length > 250
      || !/^[a-zA-Z0-9_-]{12,120}$/.test(requestId)) {
      res.status(400).json({ success: false, message: "Invalid feedback, User ID, or request identifier." });
      return;
    }

    let uid = "";
    const authorization = String(req.get("Authorization") || "");
    if (authorization) {
      const match = authorization.match(/^Bearer (.+)$/i);
      if (!match) {
        res.status(401).json({ success: false, message: "Invalid FieldWorks authentication." });
        return;
      }
      try {
        const verified = await admin.auth().verifyIdToken(match[1]);
        uid = verified.uid; // Always trust verified Firebase UID, never a supplied account ID.
      } catch (_) {
        res.status(401).json({ success: false, message: "Your FieldWorks session expired. Please sign in again." });
        return;
      }
    } else if (/^guest-[a-z0-9-]{10,100}$/.test(proposedUid)) {
      uid = proposedUid; // Persistent local guest ID; not a verified identity.
    } else {
      res.status(401).json({ success: false, message: "Please use a valid FieldWorks account or guest ID." });
      return;
    }

    try {
      const now = Date.now();
      const today = new Date(now).toISOString().slice(0, 10);
      const remoteAddress = String(req.ip || req.socket?.remoteAddress || "unknown");
      const ipFingerprint = createHash("sha256").update(remoteAddress, "utf8").digest("hex");
      const receiptFingerprint = createHash("sha256").update(uid + "\n" + requestId, "utf8").digest("hex");
      const counterRef = db.collection("fieldWorksCounters").doc("fieldWorksFeedback");
      const receiptRef = db.collection("fieldWorksFeedbackRequests").doc(receiptFingerprint);
      const ipRef = db.collection("fieldWorksFeedbackRateLimits").doc(ipFingerprint);
      const dailyRef = db.collection("fieldWorksFeedbackRateLimits").doc("daily-" + today);

      const result = await db.runTransaction(async (tx) => {
        // All reads happen before writes. Duplicate mobile requests return
        // their original feedback ID instead of incrementing the counter.
        const [counter, receipt, ipLimit, dailyLimit] = await Promise.all([
          tx.get(counterRef), tx.get(receiptRef), tx.get(ipRef), tx.get(dailyRef),
        ]);
        if (receipt.exists) {
          const savedId = Number(receipt.get("feedback_id"));
          if (!Number.isSafeInteger(savedId) || savedId < 1) throw httpError(500, "Invalid receipt.");
          return { id: savedId, duplicate: true };
        }
        const perIp = ipLimit.exists && ipLimit.get("day") === today ? ipLimit.data() : {};
        const global = dailyLimit.exists ? dailyLimit.data() : {};
        if (Number(perIp.lastAttemptAt || 0) + 15000 > now) {
          throw httpError(429, "Feedback was just submitted. Please try again shortly.");
        }
        if (Number(perIp.count || 0) >= 25 || Number(global.count || 0) >= 500) {
          throw httpError(429, "The feedback submission limit has been reached. Please try later.");
        }
        const previous = counter.exists ? Number(counter.get("lastId") || 0) : 0;
        const nextId = previous + 1;
        if (!Number.isSafeInteger(nextId) || nextId < 1) throw httpError(500, "Invalid feedback counter.");
        const feedbackRef = db.collection("fieldWorksFeedback").doc(String(nextId));
        tx.create(feedbackRef, {
          timestamp: admin.firestore.FieldValue.serverTimestamp(),
          userid: uid,
          feedback_message: message,
        });
        tx.set(counterRef, {
          lastId: nextId,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        tx.create(receiptRef, {
          feedback_id: nextId,
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        tx.set(ipRef, {
          day: today, count: Number(perIp.count || 0) + 1, lastAttemptAt: now,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        tx.set(dailyRef, {
          day: today, count: Number(global.count || 0) + 1,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        return { id: nextId, duplicate: false };
      });
      res.status(200).json({
        success: true, feedback_id: result.id,
        message: "Feedback #" + result.id + " saved successfully.",
      });
    } catch (error) {
      const status = [400, 401, 429, 503].includes(Number(error.status))
        ? Number(error.status) : 500;
      console.error("FieldWorks feedback storage failed:", status, error.code || "unknown");
      res.status(status).json({
        success: false,
        message: status === 429 ? error.message : "Could not save feedback to Firebase. Please retry.",
      });
    }
  }
);
