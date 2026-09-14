const JOURNEY = "POST_MATRIC_SCHOLARSHIP";
const KEY_CITIZEN = "mhScholarshipCitizen";
const KEY_REF = "mhScholarshipRef";
const DEPTS = [
  {
    code: "REVENUE",
    name: "Revenue Department",
    hi: "महसूल विभाग",
    shares: "income and caste",
  },
  {
    code: "EDUCATION",
    name: "Education Department",
    hi: "शिक्षण विभाग",
    shares: "marks",
  },
  {
    code: "DBT",
    name: "Direct Benefit Transfer (DBT)",
    hi: "थेट लाभ हस्तांतरण",
    shares: "bank account for scholarship payment",
  },
];

const STATUS_COPY = {
  SUBMITTED: { kind: "ok", text: "Submitted — your application has been received." },
  VERIFIED: { kind: "ok", text: "Completed — department records were received for eligibility." },
  PARTIALLY_VERIFIED: {
    kind: "warn",
    text: "In progress — some department records still needed. You may need action if a record cannot be fetched.",
  },
  REJECTED: { kind: "bad", text: "Needs action — this application was not approved." },
  CLOSED: { kind: "ok", text: "Completed — this application is closed." },
  FAILED: { kind: "bad", text: "Needs action — a department record could not be fetched." },
};

const STEP_COPY = {
  INCOME_CERTIFICATE: "Income record (Revenue)",
  CASTE_CERTIFICATE: "Caste record (Revenue)",
  MARKS: "Marks (Education)",
  BANK_ACCOUNT: "Bank account (DBT)",
};

const STEP_STATUS = {
  COMPLETED: "Received",
  PENDING_SOURCE: "Waiting for the department",
  FAILED: "Could not fetch — needs action",
};

async function api(path, opts) {
  const res = await fetch(path, opts);
  const text = await res.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!res.ok) {
    const detail = body && typeof body === "object" ? body.detail || body.title || body.message : body;
    const err = new Error(detail || res.status + " " + res.statusText);
    err.status = res.status;
    throw err;
  }
  return body;
}

function esc(v) {
  return String(v == null ? "" : v)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function announce(text) {
  document.getElementById("live").textContent = text;
}

function setStatus(el, kind, text) {
  if (!el) return;
  el.className = "status " + (kind || "");
  el.textContent = text || "";
}

function setBusy(el, busy, label) {
  if (!el) return;
  if (busy) {
    if (!el.dataset.label) el.dataset.label = el.textContent;
    el.disabled = true;
    el.setAttribute("aria-busy", "true");
    if (label) el.textContent = label;
  } else {
    el.disabled = false;
    el.removeAttribute("aria-busy");
    if (el.dataset.label) el.textContent = el.dataset.label;
  }
}

function citizenId() {
  return sessionStorage.getItem(KEY_CITIZEN);
}

function currentView() {
  const h = (location.hash || "#scheme").replace("#", "");
  return ["scheme", "apply", "status", "officer"].includes(h) ? h : "scheme";
}

function showView() {
  const view = currentView();
  ["scheme", "apply", "status", "officer"].forEach((name) => {
    const el = document.getElementById("view-" + name);
    if (el) el.hidden = name !== view;
  });
  document.querySelectorAll("[data-nav]").forEach((a) => {
    if (a.dataset.nav === view) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  });
  if (view === "apply") renderDepts();
  if (view === "officer") loadOfficer();
  if (view === "status") {
    const ref = new URLSearchParams(location.search).get("ref") || sessionStorage.getItem(KEY_REF);
    if (ref) {
      document.getElementById("referenceNo").value = ref;
      loadStatus(ref);
    }
  }
}

function markStep(name) {
  document.querySelectorAll(".steps [data-step]").forEach((li) => {
    li.classList.toggle("now", li.dataset.step === name);
  });
}

function showApplyPanel(name) {
  ["details", "connect", "consent"].forEach((p) => {
    const el = document.getElementById("panel-" + p);
    if (el) el.hidden = p !== name;
  });
  markStep(name === "consent" ? "consent" : name);
}

document.getElementById("detailsForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const btn = event.target.querySelector("[type=submit]");
  setBusy(btn, true, "Saving…");
  try {
    const given = document.getElementById("givenName").value.trim();
    const family = document.getElementById("familyName").value.trim();
    const id = await api("/api/identity/citizens", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        nameLatin: given + " " + family,
        nameDevanagari: document.getElementById("nameDevanagari").value.trim() || given,
        givenName: given,
        familyName: family,
        fatherName: document.getElementById("fatherName").value.trim(),
        dob: document.getElementById("dob").value,
        dobPrecision: "DAY",
        gender: document.querySelector("[name=gender]:checked").value,
        contactMasked: "99****00",
      }),
    });
    sessionStorage.setItem(KEY_CITIZEN, id);
    announce("Applicant saved. Connect department accounts next.");
    showApplyPanel("connect");
    await renderDepts();
  } catch (e) {
    setStatus(document.getElementById("connectStatus"), "bad", friendly(e));
  } finally {
    setBusy(btn, false);
  }
});

async function linkedCodes() {
  const id = citizenId();
  if (!id) return new Set();
  const links = await api("/api/identity/citizens/" + encodeURIComponent(id) + "/links");
  return new Set((links || []).map((l) => l.departmentCode));
}

async function renderDepts() {
  const list = document.getElementById("deptList");
  if (!list) return;
  let linked = new Set();
  try {
    linked = await linkedCodes();
  } catch (e) {
    setStatus(document.getElementById("connectStatus"), "bad", friendly(e));
  }
  list.innerHTML = DEPTS.map((d) => {
    const ok = linked.has(d.code);
    return `<li class="dept-card" data-dept="${esc(d.code)}">
      <h3>${esc(d.name)} <span lang="hi" class="hi">/ ${esc(d.hi)}</span>
        <span class="badge ${ok ? "ok" : ""}">${ok ? "Connected" : "Not connected"}</span></h3>
      <p>Needed for this scheme: ${esc(d.shares)}.</p>
      ${
        ok
          ? ""
          : `<div class="row">
        <button type="button" class="btn primary" data-proof="digilocker">Connect with DigiLocker demo</button>
        <button type="button" class="btn" data-proof="otp">Connect with local ID + OTP (demo)</button>
      </div>`
      }
    </li>`;
  }).join("");
  document.getElementById("toConsent").disabled = DEPTS.some((d) => !linked.has(d.code));
}

document.getElementById("deptList").addEventListener("click", (event) => {
  const btn = event.target.closest("[data-proof]");
  if (!btn) return;
  const card = btn.closest("[data-dept]");
  openProof(card.dataset.dept, btn.dataset.proof);
});

let pendingProof = null;

function openProof(dept, kind) {
  if (!citizenId()) {
    setStatus(document.getElementById("connectStatus"), "warn", "Fill applicant details first.");
    showApplyPanel("details");
    return;
  }
  pendingProof = { dept, kind };
  const dlg = document.getElementById("proofDlg");
  const otp = document.getElementById("otpFields");
  const title = document.getElementById("proofTitle");
  const body = document.getElementById("proofBody");
  const deptName = DEPTS.find((d) => d.code === dept).name;
  if (kind === "digilocker") {
    otp.hidden = true;
    title.textContent = "DigiLocker-shaped demo";
    body.textContent =
      "This is a DigiLocker-shaped mock for " +
      deptName +
      ". It is not live DigiLocker and not live SSO. No DigiLocker credentials are sent.";
    document.getElementById("proofConfirm").textContent = "Connect with demo proof";
  } else {
    otp.hidden = false;
    title.textContent = "Local ID + OTP (demo)";
    body.textContent =
      "Verify " +
      deptName +
      " with a local ID and OTP. This is a labelled demo — not a live telecom OTP and not live SSO.";
    document.getElementById("localId").value = "MH-" + dept + "-DEMO";
    document.getElementById("otp").value = "";
    document.getElementById("proofConfirm").textContent = "Verify demo OTP";
  }
  dlg.showModal();
}

document.getElementById("proofForm").addEventListener("submit", async (event) => {
  const submitter = event.submitter;
  if (!submitter || submitter.value === "cancel" || !pendingProof) return;
  event.preventDefault();
  const { dept, kind } = pendingProof;
  if (kind === "otp") {
    const otp = document.getElementById("otp").value.trim();
    if (!/^\d{6}$/.test(otp)) {
      document.getElementById("otp").setAttribute("aria-invalid", "true");
      return;
    }
    document.getElementById("otp").removeAttribute("aria-invalid");
  }
  const localId =
    kind === "otp"
      ? document.getElementById("localId").value.trim() + "-" + Date.now()
      : "DEMO-" + dept + "-" + Date.now();
  const btn = document.getElementById("proofConfirm");
  setBusy(btn, true, "Connecting…");
  try {
    await api("/api/identity/links", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        citizenId: citizenId(),
        departmentCode: dept,
        localIdType: dept,
        localId,
        proof: kind === "digilocker" ? "digilocker-demo-mock" : "local-otp-demo",
      }),
    });
    document.getElementById("proofDlg").close();
    pendingProof = null;
    setStatus(document.getElementById("connectStatus"), "ok", "Account connected.");
    announce("Department account connected");
    await renderDepts();
  } catch (e) {
    setStatus(document.getElementById("connectStatus"), "bad", friendly(e));
  } finally {
    setBusy(btn, false);
  }
});

document.getElementById("toConsent").addEventListener("click", () => {
  showApplyPanel("consent");
  markStep("consent");
});

document.getElementById("consentForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const btn = document.getElementById("submitBtn");
  const statusEl = document.getElementById("submitStatus");
  if (!citizenId()) {
    setStatus(statusEl, "warn", "Fill applicant details and connect accounts first.");
    return;
  }
  setBusy(btn, true, "Submitting…");
  try {
    const req = await api("/api/consent/requests", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        citizenId: citizenId(),
        requesterId: "SCHOLARSHIP",
        purposeCode: "SCHOLARSHIP_ELIGIBILITY",
        purposeText: "share income & caste from Revenue, marks from Education, bank from DBT for scholarship eligibility",
        categories: ["INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT"],
      }),
    });
    await api("/api/consent/requests/" + req.id + "/grant", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Auth-Jti": "ui-session" },
      body: JSON.stringify({ citizenId: citizenId() }),
    });
    await api("/api/journeys/" + encodeURIComponent(JOURNEY) + "/start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ citizenId: citizenId(), submission: {} }),
    });
    let ref = null;
    for (let i = 0; i < 40; i++) {
      const apps = await api("/api/applications?citizenId=" + encodeURIComponent(citizenId()) + "&size=5");
      if (apps && apps[0]) {
        ref = apps[0].referenceNo;
        break;
      }
      await new Promise((r) => setTimeout(r, 250));
    }
    if (!ref) throw new Error("Submitted, but the application number is not ready yet. Use Track application in a moment.");
    sessionStorage.setItem(KEY_REF, ref);
    markStep("submit");
    announce("Application submitted");
    location.hash = "status";
    history.replaceState(null, "", "/scholarship/#status");
    document.getElementById("referenceNo").value = ref;
    showView();
    await loadStatus(ref);
  } catch (e) {
    setStatus(statusEl, "bad", friendly(e));
  } finally {
    setBusy(btn, false);
  }
});

document.getElementById("trackForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const ref = document.getElementById("referenceNo").value.trim();
  sessionStorage.setItem(KEY_REF, ref);
  await loadStatus(ref);
});

function humanStatus(code) {
  return STATUS_COPY[code] || { kind: "warn", text: "In progress — the application is being processed." };
}

function humanStep(step) {
  const name = STEP_COPY[step.stepCode] || "Department record";
  const st = STEP_STATUS[step.status] || "In progress";
  return name + " — " + st;
}

async function loadStatus(ref) {
  const msg = document.getElementById("statusMsg");
  const card = document.getElementById("statusCard");
  setStatus(msg, "", "Checking…");
  try {
    const app = await api("/api/applications/" + encodeURIComponent(ref));
    const steps = await api("/api/applications/" + encodeURIComponent(ref) + "/steps");
    const status = humanStatus(app.status);
    setStatus(msg, status.kind, status.text);
    const items = (steps || []).map((s) => `<li>${esc(humanStep(s))}</li>`).join("");
    card.innerHTML = `<article class="card">
      <p><strong>Application number:</strong> ${esc(app.referenceNo)}</p>
      <p><strong>Scheme:</strong> Post-matric scholarship</p>
      <p><strong>Status:</strong> ${esc(status.text)}</p>
      <h2>Records requested</h2>
      <ul>${items || "<li>Waiting for department checks to appear.</li>"}</ul>
    </article>`;
  } catch (e) {
    card.innerHTML = "";
    setStatus(msg, "bad", friendly(e));
  }
}

async function loadOfficer() {
  const msg = document.getElementById("officerMsg");
  const box = document.getElementById("officerTable");
  setStatus(msg, "", "Loading…");
  try {
    const apps = await api("/api/applications?size=20");
    const rows = (apps || []).filter((a) => a.journeyCode === JOURNEY);
    if (!rows.length) {
      box.innerHTML = "<p>No scholarship applications yet.</p>";
      setStatus(msg, "", "");
      return;
    }
    box.innerHTML = `<div class="table-wrap"><table>
      <caption>Scholarship applications</caption>
      <thead><tr><th scope="col">Application number</th><th scope="col">Scheme</th><th scope="col">Status</th></tr></thead>
      <tbody>${rows
        .map((a) => {
          const st = humanStatus(a.status);
          return `<tr><td><a href="#status" data-ref="${esc(a.referenceNo)}">${esc(a.referenceNo)}</a></td>
            <td>Post-matric scholarship</td><td>${esc(st.text)}</td></tr>`;
        })
        .join("")}</tbody></table></div>`;
    setStatus(msg, "ok", rows.length + " application(s).");
  } catch (e) {
    box.innerHTML = "";
    setStatus(msg, "bad", friendly(e));
  }
}

document.getElementById("officerTable").addEventListener("click", (event) => {
  const a = event.target.closest("a[data-ref]");
  if (!a) return;
  event.preventDefault();
  sessionStorage.setItem(KEY_REF, a.dataset.ref);
  location.hash = "status";
});

document.getElementById("officerRefresh").addEventListener("click", () => loadOfficer());

function friendly(e) {
  const msg = (e && e.message) || "Something went wrong. Try again.";
  if (/Failed to fetch|NetworkError/i.test(msg)) {
    return "The scholarship service could not be reached. Confirm the application is running.";
  }
  if (e && e.status === 400) return "That request was not accepted. Check the form and try again.";
  if (e && e.status === 404) return "That application number was not found.";
  if (e && e.status >= 500) return "The service could not complete that request. Try again in a moment.";
  if (msg.length > 180) return "The request could not be completed. Try again.";
  return msg;
}

document.addEventListener("blur", (event) => {
  const input = event.target;
  if (!input.matches?.("input")) return;
  if (input.matches(":user-invalid")) input.setAttribute("aria-invalid", "true");
  else input.removeAttribute("aria-invalid");
}, true);

document.addEventListener("input", (event) => {
  if (event.target.hasAttribute?.("aria-invalid")) {
    if (!event.target.matches(":user-invalid")) event.target.removeAttribute("aria-invalid");
  }
});

document.querySelectorAll("[data-nav]").forEach((a) => {
  a.addEventListener("click", () => {
    location.hash = a.dataset.nav;
  });
});

window.addEventListener("hashchange", showView);
showApplyPanel("details");
showView();
