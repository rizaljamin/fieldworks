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
