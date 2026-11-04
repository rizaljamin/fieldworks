(function () {
  "use strict";
  var pendingRequestId = "";
  var pendingSignature = "";
  var sending = false;
  var ui = null;

  function guestId() {
    var key = "fieldworks.feedback.guest-id.v1";
    var id = "";
    try { id = localStorage.getItem(key) || ""; } catch (_) {}
    if (!id) {
      id = "guest-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 12);
      try { localStorage.setItem(key, id); } catch (_) {}
    }
    return id;
  }

  function currentFeedbackUserId() {
    var uid = "";
    try {
      if (window.FieldWorksAuth && typeof window.FieldWorksAuth.currentUid === "function") {
        uid = String(window.FieldWorksAuth.currentUid() || "").trim();
      }
    } catch (_) {}
    return uid || guestId();
  }

  function requestIdFor(message, userid) {
    var signature = userid + "\u0001" + message;
    if (pendingSignature !== signature || !pendingRequestId) {
      pendingSignature = signature;
      pendingRequestId = "fw-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 12);
    }
    return pendingRequestId;
  }

  function makeUI() {
    if (ui) return ui;
    var style = document.createElement("style");
    style.textContent =
      ".fw-feedback-overlay{position:fixed;inset:0;z-index:80;background:#082c507a;display:grid;place-items:center;padding:16px}" +
      ".fw-feedback-overlay[hidden]{display:none}" +
      ".fw-feedback-card{width:min(100%,470px);max-height:95vh;overflow:auto;border-radius:18px;background:#fffefa;padding:22px;box-shadow:0 20px 65px #082c5050;color:#16364c}" +
      ".fw-feedback-head{display:flex;align-items:center;justify-content:space-between;gap:12px}" +
      ".fw-feedback-head h2{font-size:20px;margin:0;color:#082c50}" +
      ".fw-feedback-close{border:0;background:#edf3ef;color:#456;padding:6px 11px;border-radius:9px;font-size:18px}" +
      ".fw-feedback-card p{font-size:12px;color:#718387;line-height:1.5}" +
      ".fw-feedback-form{display:grid;gap:13px}" +
      ".fw-feedback-form label{display:grid;gap:6px;font-size:12px;font-weight:700}" +
      ".fw-feedback-form textarea,.fw-feedback-form input{width:100%;border:1px solid #d9e4de;border-radius:10px;background:#fff;padding:12px;font-size:13px;color:#16364c}" +
      ".fw-feedback-form textarea{min-height:130px;resize:vertical}" +
      ".fw-feedback-form small{font-size:11px;color:#718387;font-weight:400}" +
      ".fw-feedback-submit{background:#138c92;color:white;border:0;border-radius:11px;padding:12px;font-weight:800}" +
      ".fw-feedback-submit:disabled{opacity:.6;cursor:wait}" +
      ".fw-feedback-status{min-height:20px;margin:0;font-size:12px;white-space:pre-wrap}" +
      ".fw-feedback-status.error{color:#b22e32}.fw-feedback-status.success{color:#187057}" +
      ".fw-feedback-destination{border:1px solid #d9e4de;border-radius:11px;background:#f5faf7;padding:10px 12px;font-size:12px;display:grid;gap:7px}" +
      ".fw-feedback-destination strong{display:inline-block;min-width:58px;color:#576d70}" +
      ".fw-feedback-userid{overflow-wrap:anywhere;font-size:11px;color:#16364c}";
    document.head.appendChild(style);

    var overlay = document.createElement("div");
    overlay.className = "fw-feedback-overlay";
    overlay.hidden = true;
    overlay.setAttribute("role", "dialog");
    overlay.setAttribute("aria-modal", "true");
    overlay.setAttribute("aria-label", "Send FieldWorks feedback");
    overlay.innerHTML =
      '<section class="fw-feedback-card">' +
      '<div class="fw-feedback-head"><h2>Send Feedback</h2><button type="button" class="fw-feedback-close" aria-label="Close feedback">×</button></div>' +
      '<p>Send feedback directly to the FieldWorks Firebase collection. No email application is needed.</p>' +
      '<div class="fw-feedback-destination" aria-label="Firebase feedback details">' +
      '<div><strong>Saved in:</strong> fieldWorksFeedback collection</div>' +
      '<div><strong>User ID:</strong> <span class="fw-feedback-userid" id="fwFeedbackUserId"></span></div>' +
      '</div>' +
      '<form class="fw-feedback-form">' +
      '<label for="fwFeedbackText">Feedback message <textarea id="fwFeedbackText" maxlength="3000" required placeholder="Tell us what works, what does not, or what to improve."></textarea></label>' +
      '<button class="fw-feedback-submit" type="submit">Submit</button>' +
      '<p class="fw-feedback-status" role="status" aria-live="polite"></p>' +
      '</form></section>';
    document.body.appendChild(overlay);

    overlay.querySelector(".fw-feedback-close").addEventListener("click", function () {
      if (!sending) overlay.hidden = true;
    });
    overlay.addEventListener("click", function (event) {
      if (event.target === overlay && !sending) overlay.hidden = true;
    });
    overlay.querySelector(".fw-feedback-form").addEventListener("submit", function (event) {
      event.preventDefault();
      if (sending) return;
      var feedback = overlay.querySelector("#fwFeedbackText").value.trim();
      var status = overlay.querySelector(".fw-feedback-status");
      if (!feedback) { status.textContent = "Please enter your feedback."; status.className = "fw-feedback-status error"; return; }
      if (!window.FieldWorksCommunity || typeof window.FieldWorksCommunity.submitFeedback !== "function") {
        status.textContent = "Firebase feedback submission is unavailable in this build.";
        status.className = "fw-feedback-status error";
        return;
      }
      var userid = currentFeedbackUserId();
      overlay.querySelector("#fwFeedbackUserId").textContent = userid;
      var requestId = requestIdFor(feedback, userid);
      sending = true;
      overlay.querySelector(".fw-feedback-submit").disabled = true;
      status.textContent = "Saving feedback to Firebase…";
      status.className = "fw-feedback-status";
      try {
        window.FieldWorksCommunity.submitFeedback(feedback, userid, requestId);
      } catch (error) {
        sending = false;
        overlay.querySelector(".fw-feedback-submit").disabled = false;
        status.textContent = "Could not send feedback. Please retry.";
        status.className = "fw-feedback-status error";
      }
    });
    ui = overlay;
    return overlay;
  }

  window.fwOpenFeedback = function () {
    var overlay = makeUI();
    overlay.querySelector("#fwFeedbackUserId").textContent = currentFeedbackUserId();
    overlay.hidden = false;
    overlay.querySelector("#fwFeedbackText").focus();
  };
  window.onFieldWorksFeedbackResult = function (result) {
    var overlay = makeUI();
    sending = false;
    overlay.querySelector(".fw-feedback-submit").disabled = false;
    var status = overlay.querySelector(".fw-feedback-status");
    if (result && result.success === true && Number(result.feedback_id) >= 1) {
      status.textContent = (result && result.message) ||
        ("Feedback #" + result.feedback_id + " saved successfully.");
      status.className = "fw-feedback-status success";
      overlay.querySelector("#fwFeedbackText").value = "";
      pendingSignature = "";
      pendingRequestId = "";
      var communityStatus = document.getElementById("fwCommunityStatus");
      if (communityStatus) communityStatus.textContent = status.textContent;
    } else {
      status.textContent = (result && result.message) || "Feedback could not be saved to Firebase. Please retry.";
      status.className = "fw-feedback-status error";
    }
  };
})();
