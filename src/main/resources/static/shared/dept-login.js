/*
 * Department login for the citizen portals (docs/contracts/login-assertion.md).
 *
 * A citizen proves who they are AT a department by logging in at that department's own login. start() asks Samanvay
 * for the department's login address (with a one-time state it will check later) and sends the browser there; when the
 * department sends the citizen back to /shared/dept-callback.html with a signed assertion, complete() hands that
 * assertion to Samanvay, which verifies it and saves the link. Nothing here decides anything: the server verifies the
 * signature, the citizen, the state and the freshness. This script only carries the request and the proof.
 *
 * Needs /shared/auth.js (citizen realm) loaded first. Exposed as window.SamanvayDeptLogin.
 */
(function () {
  const KEY = "samanvay.deptLogin";
  const RETURNED = "samanvay.deptLogin.returned";
  const CALLBACK_PATH = "/shared/dept-callback.html";

  async function call(path, body) {
    const res = await window.SamanvayAuth.fetch(
      path,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) },
      "citizen"
    );
    const text = await res.text();
    let parsed = null;
    try {
      parsed = text ? JSON.parse(text) : null;
    } catch {
      parsed = text;
    }
    if (!res.ok) {
      const detail = parsed && typeof parsed === "object" ? parsed.detail || parsed.title || parsed.message : parsed;
      throw new Error(detail || res.status + " " + res.statusText);
    }
    return parsed;
  }

  function isHttp(url) {
    try {
      const scheme = new URL(url).protocol;
      return scheme === "https:" || scheme === "http:";
    } catch {
      return false;
    }
  }

  /** Only a path on this site: never another origin, a protocol-relative address, or a script. */
  function safeReturnPath(path) {
    return typeof path === "string" && path.startsWith("/") && !path.startsWith("//") ? path : "/";
  }

  function readPending() {
    try {
      const p = JSON.parse(sessionStorage.getItem(KEY) || "null");
      return p && p.citizenId && p.departmentCode ? p : null;
    } catch {
      return null;
    }
  }

  const api = {
    /** Test seam: where the browser is sent. */
    navigate: (url) => window.location.assign(url),

    async start({ citizenId, departmentCode, returnPath }) {
      const res = await call("/api/identity/department-login", {
        citizenId,
        departmentCode,
        returnTo: window.location.origin + CALLBACK_PATH,
      });
      if (!res || !isHttp(res.loginUrl)) {
        throw new Error("The department's login address is not usable.");
      }
      sessionStorage.setItem(KEY, JSON.stringify({ citizenId, departmentCode, returnPath }));
      api.navigate(res.loginUrl);
    },

    async complete(search) {
      const pending = readPending();
      // A login is used once: forget it before anything else can go wrong.
      sessionStorage.removeItem(KEY);
      if (!pending) {
        throw new Error("This department login was not started here. Please start again.");
      }
      const params = new URLSearchParams(search || "");
      if (params.get("error")) {
        throw new Error("The department login did not complete. Please try again.");
      }
      const assertion = params.get("assertion");
      if (!assertion) {
        throw new Error("The department did not send back a login result. Please try again.");
      }
      await call("/api/identity/links", {
        citizenId: pending.citizenId,
        departmentCode: pending.departmentCode,
        localIdType: "",
        localId: "",
        provider: "DEPT_ASSERTION",
        proof: assertion,
      });
      sessionStorage.setItem(RETURNED, "1");
      return { returnPath: safeReturnPath(pending.returnPath) };
    },

    /** True once after a successful department login, so the portal can reopen the connect-accounts step. */
    takeReturned() {
      const yes = sessionStorage.getItem(RETURNED) === "1";
      sessionStorage.removeItem(RETURNED);
      return yes;
    },
  };

  window.SamanvayDeptLogin = api;
})();
