const NAV = [
  ["/", "Command", false],
  ["/journey.html", "Journey", false],
  ["/ops.html", "Incident", false],
  ["/audit.html", "Audit", false],
  ["/onboard.html", "Onboard", true],
  ["/caller.html", "Caller", true],
];

function mountShell(active) {
  const root = document.getElementById("shell");
  const here = active || location.pathname;
  root.insertAdjacentHTML(
    "afterbegin",
    `<header class="mast">
      <div class="brand"><em>समन्वय</em> SAMANVAY</div>
      <div class="tag">Interoperability infrastructure — control plane console. Not a citizen service portal.</div>
    </header>
    <nav class="bar">${NAV.map(([href, label, side]) => {
      const on = here === href || (href !== "/" && here.endsWith(href));
      return `<a href="${href}" class="${on ? "active" : ""} ${side ? "side" : ""}">${label}${side ? " · tool" : ""}</a>`;
    }).join("")}</nav>`
  );
}

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
    err.body = body;
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

function when(v) {
  return v ? new Date(v).toISOString().replace("T", " ").replace(/\..+/, "Z") : "—";
}

function tone(status) {
  const s = String(status || "").toUpperCase();
  if (["GREEN", "ACTIVE", "PUBLISHED", "VERIFIED", "COMPLETED", "GRANTED", "ALLOWED", "OK"].includes(s)) return "ok";
  if (["AMBER", "PENDING_SOURCE", "PARTIALLY_VERIFIED", "SUBMITTED", "UNKNOWN"].includes(s)) return "warn";
  if (["RED", "FAILED", "DENIED", "REVOKED", "BREACH"].includes(s)) return "bad";
  return "";
}

function qs(name) {
  return new URLSearchParams(location.search).get(name);
}

function unwrap(v) {
  if (v == null) return "—";
  if (typeof v === "object") {
    if (v.empty) return "—";
    if (Object.prototype.hasOwnProperty.call(v, "value")) return unwrap(v.value);
  }
  return String(v);
}

function showErr(el, e) {
  el.innerHTML = `<p class="err">${esc(e.message || e)}</p>`;
}
