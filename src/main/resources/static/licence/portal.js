const JOURNEY = "BUSINESS_NOC";
const KEY_CITIZEN = "mhLicenceCitizen";
const KEY_REF = "mhLicenceRef";
const KEY_OFFICER = "mhLicenceOfficer";
const KEY_LANG = "mhLicenceLang";
const KEY_CASE = "mhLicenceOfficerCase";
const DEPTS = [
  { code: "MUNICIPAL", name: "Municipal Corporation", hi: "नगरपालिका", shares: "property" },
  { code: "FIRE", name: "Fire Services", hi: "अग्निशमन", shares: "fire NOC" },
  { code: "POLLUTION", name: "Pollution Control Board", hi: "प्रदूषण नियंत्रण", shares: "pollution clearance" },
  { code: "REVENUE", name: "Revenue Department", hi: "महसूल विभाग", shares: "land record" },
];

const STATUS_COPY = {
  SUBMITTED: { kind: "ok", text: "Submitted — your application has been received." },
  VERIFIED: { kind: "ok", text: "Completed — department records were received for eligibility." },
  REJECTED: { kind: "bad", text: "Needs action — this application was not approved." },
  CLOSED: { kind: "ok", text: "Completed — this application is closed." },
  FAILED: { kind: "bad", text: "Needs action — a department record could not be fetched." },
};

const I18N = {
  en: {
    "nav.scheme": "Scheme",
    "nav.apply": "Apply",
    "nav.status": "Track application",
    "nav.officer": "Officer desk",
    "scheme.title": "Business licence / NOC",
    "scheme.lede":
      "Permission to start a premises in Maharashtra. This service uses records already held by Municipal, Fire, Pollution, and Revenue, after you consent.",
    "apply.title": "Apply for licence",
    "officer.title": "Officer desk",
    "officer.lede":
      "Review applications for this scheme. Status is written for officers: submitted, in progress, needs action, or completed. Open an application number to see which department records have arrived. You do not need technical codes.",
  },
  mr: {
    "nav.scheme": "योजना",
    "nav.apply": "अर्ज",
    "nav.status": "अर्जाचा पाठपुरावा",
    "nav.officer": "अधिकारी कक्ष",
    "scheme.title": "व्यवसाय परवाना / ना-हरकत",
    "scheme.lede":
      "महाराष्ट्रात परिसर सुरू करण्याची परवानगी. संमतीनंतर नगरपालिका, अग्निशमन, प्रदूषण आणि महसूल यांच्या नोंदी वापरल्या जातात.",
    "apply.title": "परवान्यासाठी अर्ज",
    "officer.title": "अधिकारी कक्ष",
    "officer.lede":
      "या योजनेतील अर्ज तपासा. स्थिती अधिकाऱ्यांसाठी आहे: प्राप्त, प्रगतीत, कृती आवश्यक, किंवा पूर्ण. कोणत्या विभाग नोंदी आल्या हे पाहण्यासाठी अर्ज क्रमांक उघडा.",
  },
};

function applyLang(lang) {
  const use = I18N[lang] ? lang : "en";
  const pack = I18N[use];
  document.documentElement.lang = use === "mr" ? "mr" : "en";
  sessionStorage.setItem(KEY_LANG, use);
  document.querySelectorAll("[data-i18n]").forEach((el) => {
    const text = pack[el.dataset.i18n];
    if (text) el.textContent = text;
  });
  const enBtn = document.getElementById("langEn");
  const mrBtn = document.getElementById("langMr");
  if (enBtn) enBtn.setAttribute("aria-pressed", String(use === "en"));
  if (mrBtn) mrBtn.setAttribute("aria-pressed", String(use === "mr"));
}

const STEP_COPY = {
  PROPERTY: "Property record (Municipal)",
  FIRE_NOC: "Fire NOC (Fire Services)",
  POLLUTION_CLEARANCE: "Pollution clearance (Pollution)",
  LAND_RECORD: "Land record (Revenue)",
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

function when(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

function currentView() {
  const h = (location.hash || "#scheme").replace("#", "");
  return ["scheme", "apply", "status", "officer"].includes(h) ? h : "scheme";
}

function showView(opts) {
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
  if (view === "officer") renderOfficer();
  if (view === "status") {
    const ref = new URLSearchParams(location.search).get("ref") || sessionStorage.getItem(KEY_REF);
    if (ref) {
      document.getElementById("referenceNo").value = ref;
      loadStatus(ref);
    }
  }
  if (opts && opts.focus) {
    const heading = document.querySelector("#view-" + view + " h1");
    if (heading) {
      heading.setAttribute("tabindex", "-1");
      heading.focus();
    }
  }
}

function markStep(name) {
  const order = ["details", "connect", "consent", "submit"];
  const idx = order.indexOf(name);
  document.querySelectorAll(".steps [data-step]").forEach((li) => {
    const i = order.indexOf(li.dataset.step);
    li.classList.toggle("now", li.dataset.step === name);
    li.classList.toggle("done", i >= 0 && i < idx);
    if (li.dataset.step === name) li.setAttribute("aria-current", "step");
    else li.removeAttribute("aria-current");
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
  const statusEl = document.getElementById("detailsStatus");
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
    setStatus(statusEl, "ok", "");
    announce("Applicant saved. Connect department accounts next.");
    showApplyPanel("connect");
    await renderDepts();
  } catch (e) {
    setStatus(statusEl, "bad", friendly(e));
  } finally {
    setBusy(btn, false);
  }
});

async function linkedCodes() {
  const id = citizenId();
  if (!id) return new Set();
  try {
    const view = await api(
      "/api/identity/citizens/" + encodeURIComponent(id) + "/connect-accounts?journeyCode=" + encodeURIComponent(JOURNEY)
    );
    return new Set((view.departments || []).filter((d) => d.linked).map((d) => d.departmentCode));
  } catch {
    const links = await api("/api/identity/citizens/" + encodeURIComponent(id) + "/links");
    return new Set((links || []).map((l) => l.departmentCode));
  }
}

function setConnectProgress(n) {
  const total = DEPTS.length;
  const el = document.getElementById("connectProgress");
  const meter = document.getElementById("connectMeter");
  if (el) el.textContent = "Linked " + n + " of " + total + " departments";
  if (meter) meter.style.width = Math.round((n / total) * 100) + "%";
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
  setConnectProgress(linked.size);
  list.innerHTML = DEPTS.map((d) => {
    const ok = linked.has(d.code);
    return `<li class="dept-card${ok ? " ok" : ""}" data-dept="${esc(d.code)}">
      <h3>${esc(d.name)} <span lang="hi" class="hi">/ ${esc(d.hi)}</span>
        <span class="badge ${ok ? "ok" : ""}">${ok ? "Connected" : "Not connected"}</span></h3>
      <p>Needed for this scheme: ${esc(d.shares)}.</p>
      ${
        ok
          ? "<p class=\"hint\">This department account is linked for this application.</p>"
          : `<div class="row">
        <button type="button" class="btn primary" data-proof="digilocker">Connect with DigiLocker sandbox</button>
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
  const err = document.getElementById("otpError");
  if (err) err.hidden = true;
  const deptName = DEPTS.find((d) => d.code === dept).name;
  if (kind === "digilocker") {
    otp.hidden = true;
    title.textContent = "DigiLocker sandbox";
    body.textContent =
      "This is a DigiLocker sandbox mock for " +
      deptName +
      ". It is not live DigiLocker and not live SSO. No DigiLocker credentials are sent.";
    document.getElementById("proofConfirm").textContent = "Connect with sandbox proof";
  } else {
    otp.hidden = false;
    title.textContent = "Local ID + OTP demo";
    body.textContent =
      "Verify " +
      deptName +
      " with a local ID and OTP demo. This is a labelled demo — not a live telecom OTP and not live SSO.";
    document.getElementById("localId").value = "MH-" + dept + "-DEMO";
    document.getElementById("otp").value = "000000";
    document.getElementById("proofConfirm").textContent = "Verify OTP demo";
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
    const err = document.getElementById("otpError");
    if (otp !== "000000") {
      document.getElementById("otp").setAttribute("aria-invalid", "true");
      if (err) err.hidden = false;
      return;
    }
    document.getElementById("otp").removeAttribute("aria-invalid");
    if (err) err.hidden = true;
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
        provider: kind === "digilocker" ? "DIGILOCKER" : "LOCAL_ID_OTP",
        proof: kind === "digilocker" ? "sandbox" : "000000",
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
        requesterId: "INDUSTRY",
        purposeCode: "BUSINESS_NOC",
        purposeText: "share property from Municipal, fire NOC from Fire, pollution clearance, land from Revenue for business licence",
        categories: ["PROPERTY", "FIRE_NOC", "POLLUTION_CLEARANCE", "LAND_RECORD"],
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
    history.replaceState(null, "", "/licence/#status");
    document.getElementById("referenceNo").value = ref;
    showView({ focus: true });
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
  if (code && String(code).startsWith("PARTIAL")) {
    return {
      kind: "warn",
      text: "In progress — some department records still needed. You may need action if a record cannot be fetched.",
    };
  }
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
    const submitted = when(app.submittedAt);
    const items = (steps || []).map((s) => `<li>${esc(humanStep(s))}</li>`).join("");
    card.innerHTML = `<article class="card">
      <p><strong>Application number:</strong> ${esc(app.referenceNo)}</p>
      <p><strong>Scheme:</strong> Business licence / NOC</p>
      ${submitted ? `<p><strong>Received on:</strong> ${esc(submitted)}</p>` : ""}
      <p><strong>Status:</strong> <span class="chip ${esc(status.kind)}">${esc(status.text)}</span></p>
      <h2>Records requested</h2>
      <ol class="timeline">${items || "<li>Waiting for department checks to appear.</li>"}</ol>
    </article>`;
  } catch (e) {
    card.innerHTML = "";
    setStatus(msg, "bad", e && e.status === 404 ? "That application number was not found." : friendly(e));
  }
}

function countLabel(rows) {
  let needs = 0;
  let progress = 0;
  let done = 0;
  rows.forEach((a) => {
    const st = humanStatus(a.status);
    if (st.kind === "bad") needs += 1;
    else if (st.kind === "warn") progress += 1;
    else done += 1;
  });
  return rows.length + " application(s) · " + done + " completed or received · " + progress + " in progress · " + needs + " need action";
}

function officerSignedIn() {
  return sessionStorage.getItem(KEY_OFFICER) === "1";
}

function renderOfficer() {
  const login = document.getElementById("officerLogin");
  const work = document.getElementById("officerWork");
  if (!login || !work) return;
  if (!officerSignedIn()) {
    login.hidden = false;
    work.hidden = true;
    return;
  }
  login.hidden = true;
  work.hidden = false;
  loadOfficer();
}

async function loadOfficer() {
  const msg = document.getElementById("officerMsg");
  const box = document.getElementById("officerTable");
  const counts = document.getElementById("officerCounts");
  if (!box) return;
  setStatus(msg, "", "Loading…");
  if (counts) counts.textContent = "";
  try {
    const apps = await api("/api/applications?size=20");
    const rows = (apps || []).filter((a) => a.journeyCode === JOURNEY);
    if (!rows.length) {
      box.innerHTML = "<p>No licence applications yet. When a citizen submits, the application appears here for review.</p>";
      setStatus(msg, "", "");
      return;
    }
    if (counts) counts.textContent = countLabel(rows);
    box.innerHTML = `<div class="table-wrap"><table>
      <caption>Review applications</caption>
      <thead><tr><th scope="col">Application number</th><th scope="col">Scheme</th><th scope="col">Status</th><th scope="col">Due by</th></tr></thead>
      <tbody>${rows
        .map((a) => {
          const st = humanStatus(a.status);
          const due = when(a.slaDueAt) || "—";
          return `<tr><td><a href="#officer" data-ref="${esc(a.referenceNo)}">${esc(a.referenceNo)}</a></td>
            <td>Business licence / NOC</td>
            <td><span class="chip ${esc(st.kind)}">${esc(st.text)}</span></td>
            <td>${esc(due)}</td></tr>`;
        })
        .join("")}</tbody></table></div>`;
    setStatus(msg, "ok", "Open an application number to review which department records have arrived.");
    const selected = sessionStorage.getItem(KEY_CASE);
    if (selected) await loadOfficerCase(selected);
  } catch (e) {
    box.innerHTML = "";
    setStatus(msg, "bad", e && e.status === 404 ? "The application list could not be loaded. Try again in a moment." : friendly(e));
  }
}

async function loadOfficerCase(ref) {
  const empty = document.getElementById("officerCaseEmpty");
  const body = document.getElementById("officerCaseBody");
  const retryBtn = document.getElementById("officerRetry");
  if (!body) return;
  sessionStorage.setItem(KEY_CASE, ref);
  try {
    const app = await api("/api/applications/" + encodeURIComponent(ref));
    const steps = await api("/api/applications/" + encodeURIComponent(ref) + "/steps");
    const exceptions = await api("/api/journeys/exceptions");
    const mine = (exceptions || []).filter((x) => app.instanceId && x.instanceId === app.instanceId);
    const status = humanStatus(app.status);
    const items = (steps || []).map((s) => `<li>${esc(humanStep(s))}</li>`).join("");
    const needsRetry = mine.length > 0 || (app.status && String(app.status).startsWith("PARTIAL"));
    if (empty) empty.hidden = true;
    body.hidden = false;
    body.innerHTML = `<article class="card">
      <p><strong>Application number:</strong> ${esc(app.referenceNo)}</p>
      <p><strong>Scheme:</strong> Business licence / NOC</p>
      <p><strong>Status:</strong> <span class="chip ${esc(status.kind)}">${esc(status.text)}</span></p>
      <h3>Department records</h3>
      <ol class="timeline">${items || "<li>Waiting for department checks to appear.</li>"}</ol>
      ${mine.length ? `<p>A department record could not be fetched. Restore the department if it was marked unavailable, then Retry.</p>` : ""}
    </article>`;
    if (retryBtn) {
      retryBtn.hidden = !needsRetry;
      retryBtn.dataset.instance = app.instanceId || "";
    }
  } catch (e) {
    if (empty) {
      empty.hidden = false;
      empty.textContent = e && e.status === 404 ? "That application number was not found." : friendly(e);
    }
    body.hidden = true;
    if (retryBtn) retryBtn.hidden = true;
  }
}

document.getElementById("officerLoginForm").addEventListener("submit", (event) => {
  event.preventDefault();
  const user = document.getElementById("officerUser").value.trim();
  const pass = document.getElementById("officerPass").value;
  if (user === "officer" && pass === "demo-2026") {
    sessionStorage.setItem(KEY_OFFICER, "1");
    setStatus(document.getElementById("officerLoginMsg"), "ok", "");
    announce("Officer desk signed in");
    renderOfficer();
    return;
  }
  setStatus(
    document.getElementById("officerLoginMsg"),
    "bad",
    "Officer ID or password was not accepted. This is a labelled demonstration login — not SSO."
  );
});

document.getElementById("officerTable").addEventListener("click", (event) => {
  const a = event.target.closest("a[data-ref]");
  if (!a) return;
  event.preventDefault();
  loadOfficerCase(a.dataset.ref);
});

document.getElementById("officerRefresh").addEventListener("click", () => loadOfficer());
document.getElementById("officerSignOut").addEventListener("click", () => {
  sessionStorage.removeItem(KEY_OFFICER);
  sessionStorage.removeItem(KEY_CASE);
  renderOfficer();
});
document.getElementById("officerRetry").addEventListener("click", async (event) => {
  const btn = event.currentTarget;
  const id = btn.dataset.instance;
  if (!id) return;
  setBusy(btn, true, "Retrying…");
  try {
    await api("/api/journeys/instances/" + encodeURIComponent(id) + "/retry", { method: "POST" });
    setStatus(document.getElementById("officerMsg"), "ok", "Retry requested. Refresh the list in a moment.");
    announce("Retry requested");
    const ref = sessionStorage.getItem(KEY_CASE);
    await loadOfficer();
    if (ref) await loadOfficerCase(ref);
  } catch (e) {
    setStatus(document.getElementById("officerMsg"), "bad", friendly(e));
  } finally {
    setBusy(btn, false);
  }
});
document.getElementById("langEn").addEventListener("click", () => applyLang("en"));
document.getElementById("langMr").addEventListener("click", () => applyLang("mr"));

function friendly(e) {
  const msg = (e && e.message) || "Something went wrong. Try again.";
  if (/Failed to fetch|NetworkError/i.test(msg)) {
    return "The licence service could not be reached. Confirm the application is running.";
  }
  if (e && e.status === 400) return "That request was not accepted. Check the form and try again.";
  if (e && e.status === 404) return "Nothing matching that request was found.";
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

document.querySelectorAll("[data-text]").forEach((btn) => {
  btn.addEventListener("click", () => {
    const size = btn.dataset.text;
    document.documentElement.style.fontSize = size === "s" ? "100%" : size === "l" ? "125%" : "112.5%";
    document.querySelectorAll("[data-text]").forEach((b) => b.setAttribute("aria-pressed", String(b === btn)));
  });
});

window.addEventListener("hashchange", () => showView({ focus: true }));
showApplyPanel("details");
applyLang(sessionStorage.getItem(KEY_LANG) || "en");
showView();
