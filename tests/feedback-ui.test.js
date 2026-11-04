"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const source = fs.readFileSync("app/src/main/assets/feedback.js", "utf8");
const THANK_YOU = "Thank You for your feedback and we will review and take necessary steps required";

function element() {
  return {
    textContent: "", value: "", hidden: false, disabled: false, className: "",
    handlers: {},
    addEventListener(event, fn) { this.handlers[event] = fn; },
    setAttribute(name, value) { this[name] = value; },
    focus() {},
  };
}
function harness({uid = "firebase-secret-uid", email = "fieldworks.user@example.com"} = {}) {
  const status = element();
  const submit = element();
  const textarea = element();
  const account = element();
  const form = element();
  const close = element();
  const pageStatus = element();
  pageStatus.textContent = "Previous feedback receipt";
  const overlay = element();
  const nodes = {
    ".fw-feedback-close": close,
    ".fw-feedback-form": form,
    ".fw-feedback-status": status,
    ".fw-feedback-submit": submit,
    "#fwFeedbackText": textarea,
    "#fwFeedbackFrom": account,
  };
  overlay.querySelector = selector => {
    assert.ok(nodes[selector], "Unexpected UI selector: " + selector);
    return nodes[selector];
  };
  const styles = [];
  const submissions = [];
  const document = {
    createElement(tag) { return tag === "div" ? overlay : element(); },
    head: { appendChild(node) { styles.push(node); } },
    body: { appendChild(node) { assert.equal(node, overlay); } },
    getElementById(id) { return id === "fwCommunityStatus" ? pageStatus : null; },
  };
  const window = {
    FieldWorksAuth: {
      currentUid() { return uid; },
      currentEmail() { return email; },
    },
    FieldWorksCommunity: {
      submitFeedback(message, userId, requestId) {
        submissions.push({ message, userId, requestId });
      },
    },
  };
  const localStorage = {
    values: new Map(),
    getItem(k) { return this.values.get(k) || null; },
    setItem(k, v) { this.values.set(k, v); },
  };
  vm.runInNewContext(source, { window, document, localStorage, Date, Math }, { timeout: 1000 });
  return { window, overlay, textarea, account, form, submit, status, pageStatus, submissions };
}

{
  const h = harness();
  h.window.fwOpenFeedback();
  assert.match(h.overlay.innerHTML, /FieldWorks Support Team/);
  assert.doesNotMatch(h.overlay.innerHTML, /User ID:|Saved in:|fieldWorksFeedback collection/);
  assert.equal(h.account.textContent, "fieldworks.user@example.com");
  assert.ok(!h.account.textContent.includes("firebase-secret-uid"));
  assert.equal(h.pageStatus.textContent, "");
  h.textarea.value = "Feedback about camera";
  h.form.handlers.submit({ preventDefault() {} });
  assert.equal(h.submissions.length, 1);
  assert.equal(h.submissions[0].userId, "firebase-secret-uid"); // UID still reaches backend
  assert.equal(h.submissions[0].message, "Feedback about camera");
  assert.equal(h.submit.disabled, true);
  h.window.onFieldWorksFeedbackResult({ success: true, feedback_id: 1, message: "Feedback #1 saved successfully." });
  assert.equal(h.status.textContent, THANK_YOU);
  assert.equal(h.pageStatus.textContent, "");
  assert.equal(h.textarea.value, "");
  assert.equal(h.submit.disabled, false);
  assert.doesNotMatch(h.status.textContent, /Feedback #/);
}
{
  const h = harness();
  h.window.fwOpenFeedback();
  h.textarea.value = "Please improve screen contrast";
  h.form.handlers.submit({ preventDefault() {} });
  h.window.onFieldWorksFeedbackResult({ success: false, message: "Service is temporarily unavailable." });
  assert.equal(h.status.textContent, "Service is temporarily unavailable.");
  assert.equal(h.textarea.value, "Please improve screen contrast"); // preserve on error
  assert.equal(h.submit.disabled, false);
}
{
  const h = harness({uid:"", email:""});
  h.window.fwOpenFeedback();
  assert.equal(h.account.textContent, "Guest");
  h.textarea.value = "Guest feedback";
  h.form.handlers.submit({ preventDefault() {} });
  assert.match(h.submissions[0].userId, /^guest-/);
}
{
  const h = harness({uid:"uid-123", email:""});
  h.window.fwOpenFeedback();
  assert.equal(h.account.textContent, "Signed-in FieldWorks account");
}
console.log("Feedback UI/recipient/sender/submit/success/error/guest flow: PASS");
