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
  return v ? new Date(v).toISOString().replace("T", " ").replace(/\..+/, "Z") : "-";
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
  if (v == null) return "-";
  if (typeof v === "object") {
    if (v.empty) return "-";
    if (Object.prototype.hasOwnProperty.call(v, "value")) return unwrap(v.value);
  }
  return String(v);
}

function looksLikeHtml(body) {
  return typeof body === "string" && /<!DOCTYPE|<html/i.test(body);
}

function friendlyError(e, hint) {
  const msg = (e && e.message) || String(e || "Request failed");
  if (/Failed to fetch|NetworkError|Load failed/i.test(msg)) {
    return "Cannot reach the control plane. Confirm the Spring app is running and open http://localhost:8080/.";
  }
  if (e && looksLikeHtml(e.body)) {
    return "The control plane API did not answer. Start the Spring app and open http://localhost:8080/. These pages only read /api/*.";
  }
  if (e && e.status === 404 && hint === "demo") {
    return "This action is available only with the demo profile. Restart with --spring.profiles.active=demo. Default boot correctly returns 404.";
  }
  if (e && e.status === 404) {
    return "The control plane could not find that resource. Check the reference and try again.";
  }
  if (e && e.status === 400) {
    return "The request was rejected. Check the fields and try again.";
  }
  if (e && e.status === 409) {
    return "The control plane refused a conflicting change. Refresh and retry.";
  }
  if (e && e.status >= 500) {
    return "The control plane could not complete that request. Retry in a moment.";
  }
  if (msg.length > 220) return "The control plane returned an error. Open technical detail only if you need the raw response.";
  return msg;
}

function showErr(el, e, hint) {
  el.innerHTML = `<p class="err" role="alert">${esc(friendlyError(e, hint))}</p>`;
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

function loading(msg) {
  return `<p class="loading">${esc(msg || "Loading live telemetry…")}</p>`;
}

function skeletonTiles(n) {
  return Array.from({ length: n }, () =>
    `<li class="tile" aria-hidden="true"><div class="skel skel-label"></div><div class="skel skel-metric"></div><div class="skel skel-sub"></div></li>`
  ).join("");
}

function skeletonRows(cols, rows) {
  const head = cols.map((h) => `<th scope="col">${esc(h)}</th>`).join("");
  const body = Array.from({ length: rows || 3 }, () =>
    `<tr>${cols.map(() => `<td><span class="skel skel-cell"></span></td>`).join("")}</tr>`
  ).join("");
  return `<div class="table-wrap"><table><caption class="loading">Loading live rows</caption><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
}

function skeletonTimeline(n) {
  return Array.from({ length: n || 4 }, (_, i) =>
    `<li><span class="stage-num" aria-hidden="true">${i + 1}</span><div><div class="skel skel-label"></div><div class="skel skel-line"></div></div></li>`
  ).join("");
}

function planeHealth(el, kind, title, detail) {
  if (!el) return;
  el.className = "health " + (kind || "");
  el.innerHTML = `<strong>${esc(title)}</strong><span>${esc(detail)}</span>`;
}

function setSpine(root, states) {
  if (!root) return;
  root.querySelectorAll("[data-beat]").forEach((li) => {
    const k = states[li.dataset.beat] || "idle";
    li.className = k;
    if (k === "now" || k === "warn" || k === "bad") li.setAttribute("aria-current", "step");
    else li.removeAttribute("aria-current");
  });
}

function emptyBox(title, whyHtml) {
  return `<div class="empty-box"><p class="empty-title">${esc(title)}</p><p class="empty">${whyHtml}</p></div>`;
}

function tableHtml(caption, headers, rowHtml) {
  return `<div class="table-wrap"><table><caption>${esc(caption)}</caption><thead><tr>${
    headers.map((h) => `<th scope="col">${esc(h)}</th>`).join("")
  }</tr></thead><tbody>${rowHtml}</tbody></table></div>`;
}

function announce(text) {
  let live = document.getElementById("live");
  if (!live) {
    live = document.createElement("p");
    live.id = "live";
    live.className = "visually-hidden";
    live.setAttribute("aria-live", "polite");
    document.body.prepend(live);
  }
  live.textContent = text;
}

function setStatus(el, kind, text) {
  if (!el) return;
  el.className = "status " + (kind || "");
  el.textContent = text;
}

function confirmDanger(opts) {
  const title = (opts && opts.title) || "Confirm";
  const body = (opts && opts.body) || "This demo action changes live control-plane state.";
  const confirmLabel = (opts && opts.confirmLabel) || "Confirm";
  return new Promise((resolve) => {
    let dlg = document.getElementById("confirmDlg");
    if (!dlg) {
      dlg = document.createElement("dialog");
      dlg.id = "confirmDlg";
      dlg.className = "confirm";
      dlg.setAttribute("aria-labelledby", "confirmTitle");
      dlg.innerHTML =
        `<h2 id="confirmTitle"></h2><p id="confirmBody"></p>` +
        `<div class="row">` +
        `<button type="button" class="danger" id="confirmYes"></button>` +
        `<button type="button" id="confirmNo">Cancel</button>` +
        `</div>`;
      document.body.appendChild(dlg);
    }
    dlg.querySelector("#confirmTitle").textContent = title;
    dlg.querySelector("#confirmBody").textContent = body;
    const yes = dlg.querySelector("#confirmYes");
    const no = dlg.querySelector("#confirmNo");
    yes.textContent = confirmLabel;
    let settled = false;
    const done = (value) => {
      if (settled) return;
      settled = true;
      if (dlg.open) dlg.close();
      resolve(value);
    };
    yes.onclick = () => done(true);
    no.onclick = () => done(false);
    dlg.addEventListener("close", () => done(false), { once: true });
    dlg.showModal();
    yes.focus();
  });
}

function syncUserInvalid(event) {
  const input = event.target;
  if (!input.matches?.("input, textarea, select")) return;
  if (input.matches(":user-invalid")) input.setAttribute("aria-invalid", "true");
  else input.removeAttribute("aria-invalid");
}

document.addEventListener("blur", syncUserInvalid, true);
document.addEventListener("input", (event) => {
  if (event.target.hasAttribute?.("aria-invalid")) syncUserInvalid(event);
});
