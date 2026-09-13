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
