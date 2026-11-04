(function () {
  "use strict";
  var active = false;
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

  function responseContact(input) {
    var provided = String(input || "").trim();
    if (provided) return provided;
    var uid = "";
    try {
      if (window.FieldWorksAuth && typeof window.FieldWorksAuth.currentUid === "function") {
        uid = String(window.FieldWorksAuth.currentUid() || "").trim();
      }
    } catch (_) {}
    return uid || guestId();
  }

  function makeUI() {
    if (ui) return ui;
    var style = document.createElement("style");
    style.textContent =
      ".fw-feedback-overlay{position:fixed;inset:0;z-index:80;background:#082c507a;display:grid;place-items:center;padding:16px}" +
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
      ".fw-feedback-status.error{color:#b22e32}.fw-feedback-status.success{color:#187057}";
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
      '<p>Feedback will be addressed to growplot2026@gmail.com. Tap Submit to open your email app, then tap Send there to deliver it.</p>' +
      '<form class="fw-feedback-form">' +
      '<label for="fwFeedbackText">Provide your feedback <textarea id="fwFeedbackText" maxlength="3000" required placeholder="Tell us what works, what does not, or what to improve."></textarea></label>' +
      '<label for="fwFeedbackContact">Contact detail (email or telephone) — optional' +
      '<input id="fwFeedbackContact" maxlength="250" autocomplete="off" placeholder="Leave blank to use your FieldWorks User ID">' +
      '<small>If blank, we use your signed-in User ID, or a guest ID when not signed in.</small></label>' +
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
        status.textContent = "Email feedback is unavailable in this version of FieldWorks.";
        status.className = "fw-feedback-status error";
        return;
      }
      var contact = responseContact(overlay.querySelector("#fwFeedbackContact").value);
      sending = true;
      overlay.querySelector(".fw-feedback-submit").disabled = true;
      status.textContent = "Preparing email draft…";
      status.className = "fw-feedback-status";
      try {
        window.FieldWorksCommunity.submitFeedback(feedback, contact);
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
    overlay.hidden = false;
    overlay.querySelector("#fwFeedbackText").focus();
  };
  window.onFieldWorksFeedbackResult = function (result) {
    var overlay = makeUI();
    sending = false;
    overlay.querySelector(".fw-feedback-submit").disabled = false;
    var status = overlay.querySelector(".fw-feedback-status");
    if (result && result.success === true) {
      status.textContent = (result && result.message) || "Email draft opened. Review and tap Send in your email app.";
      status.className = "fw-feedback-status success";
    } else {
      status.textContent = (result && result.message) || "Could not open the email draft. Please retry.";
      status.className = "fw-feedback-status error";
    }
  };
})();
