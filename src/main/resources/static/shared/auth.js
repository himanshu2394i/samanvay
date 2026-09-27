/*
 * Samanvay browser sign-in (dev): OIDC Authorization Code + PKCE against the
 * staff or citizen Keycloak realm, plus a paste-token fallback for scripted demos.
 * Access tokens live in sessionStorage only (per tab, gone on close); no refresh
 * tokens are kept. Every /api call must carry "Authorization: Bearer <token>";
 * the server takes the actor only from that token.
 *
 * Config: set window.SAMANVAY_AUTH before this script to override the defaults
 * (e.g. a non-localhost Keycloak). The page's default realm comes from the
 * data-realm attribute on the <script> tag ("staff" or "citizen").
 */
(function () {
  const script = document.currentScript;
  const defaults = {
    keycloakUrl: "http://localhost:8180",
    realms: {
      staff: { realm: "samanvay-staff", clientId: "samanvay-staff-ui" },
      citizen: { realm: "samanvay-citizen", clientId: "samanvay-citizen-ui" },
    },
  };
  const cfg = Object.assign({}, defaults, window.SAMANVAY_AUTH || {});
  const pageRealm = (script && script.dataset.realm) || "staff";
  const KEY_TOKEN = (r) => "samanvay.auth.token." + r;
  const KEY_ACTIVE = "samanvay.auth.active";
  const KEY_PKCE = "samanvay.auth.pkce";

  function oidc(realmKey) {
    const r = cfg.realms[realmKey];
    return cfg.keycloakUrl + "/realms/" + r.realm + "/protocol/openid-connect";
  }

  function b64url(bytes) {
    let s = "";
    bytes.forEach((b) => (s += String.fromCharCode(b)));
    return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  function randomString() {
    const a = new Uint8Array(32);
    crypto.getRandomValues(a);
    return b64url(a);
  }

  function claims(token) {
    try {
      const part = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
      return JSON.parse(decodeURIComponent(escape(atob(part))));
    } catch (e) {
      return null;
    }
  }

  function token(realmKey) {
    const t = sessionStorage.getItem(KEY_TOKEN(realmKey));
    if (!t) return null;
    const c = claims(t);
    if (!c || (c.exp && c.exp * 1000 < Date.now())) {
      sessionStorage.removeItem(KEY_TOKEN(realmKey));
      return null;
    }
    return t;
  }

  function activeRealm() {
    return sessionStorage.getItem(KEY_ACTIVE) || pageRealm;
  }

  function useRealm(realmKey) {
    if (cfg.realms[realmKey]) sessionStorage.setItem(KEY_ACTIVE, realmKey);
    render();
  }

  async function login(realmKey) {
    const realm = realmKey || activeRealm();
    const verifier = randomString();
    const state = randomString();
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
    const redirectUri = location.origin + location.pathname;
    sessionStorage.setItem(KEY_PKCE, JSON.stringify({ realm, verifier, state, redirectUri, hash: location.hash }));
    const q = new URLSearchParams({
      client_id: cfg.realms[realm].clientId,
      response_type: "code",
      scope: "openid",
      redirect_uri: redirectUri,
      state,
      code_challenge: b64url(new Uint8Array(digest)),
      code_challenge_method: "S256",
    });
    location.assign(oidc(realm) + "/auth?" + q.toString());
  }

  async function completeLogin() {
    const params = new URLSearchParams(location.search);
    const pending = JSON.parse(sessionStorage.getItem(KEY_PKCE) || "null");
    if (!params.has("code") || !pending) return;
    sessionStorage.removeItem(KEY_PKCE);
    const clean = () => history.replaceState(null, "", pending.redirectUri + (pending.hash || ""));
    if (params.get("state") !== pending.state) {
      clean();
      return;
    }
    const res = await fetch(oidc(pending.realm) + "/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        client_id: cfg.realms[pending.realm].clientId,
        code: params.get("code"),
        redirect_uri: pending.redirectUri,
        code_verifier: pending.verifier,
      }),
    });
    clean();
    if (res.ok) {
      const body = await res.json();
      sessionStorage.setItem(KEY_TOKEN(pending.realm), body.access_token);
      sessionStorage.setItem(KEY_ACTIVE, pending.realm);
    }
  }

  function pasteToken(realmKey, value) {
    const t = (value || "").trim().replace(/^Bearer\s+/i, "");
    if (!claims(t)) throw new Error("That does not look like a JWT access token.");
    sessionStorage.setItem(KEY_TOKEN(realmKey), t);
    sessionStorage.setItem(KEY_ACTIVE, realmKey);
    render();
  }

  function logout(realmKey) {
    sessionStorage.removeItem(KEY_TOKEN(realmKey || activeRealm()));
    render();
  }

  /** Returns a copy of headers with the bearer token of the given (or active) realm. */
  function withAuth(headers, realmKey) {
    const h = new Headers(headers || {});
    const t = token(realmKey || activeRealm());
    if (t) h.set("Authorization", "Bearer " + t);
    return h;
  }

  /** fetch() for /api calls with the bearer token attached. */
  function authFetch(path, opts, realmKey) {
    const o = Object.assign({}, opts || {});
    o.headers = withAuth(o.headers, realmKey);
    return fetch(path, o);
  }

  function describe(realmKey) {
    const t = token(realmKey);
    if (!t) return "not signed in";
    const c = claims(t) || {};
    return c.preferred_username || c.client_id || c.sub || "signed in";
  }

  function render() {
    let bar = document.getElementById("samanvay-auth");
    if (!bar) {
      if (!document.body) return;
      bar = document.createElement("div");
      bar.id = "samanvay-auth";
      bar.className = "auth-bar";
      bar.setAttribute("role", "region");
      bar.setAttribute("aria-label", "Sign-in");
      bar.style.cssText =
        "font:13px/1.4 system-ui,sans-serif;padding:6px 12px;border-bottom:1px solid #ccc;background:#f6f6f2;display:flex;gap:8px;flex-wrap:wrap;align-items:center";
      document.body.insertBefore(bar, document.body.firstChild);
    }
    const active = activeRealm();
    bar.innerHTML = "";
    const label = document.createElement("span");
    label.textContent = "Dev sign-in (local Keycloak) - acting as:";
    bar.appendChild(label);
    const select = document.createElement("select");
    select.setAttribute("aria-label", "Realm");
    Object.keys(cfg.realms).forEach((k) => {
      const o = document.createElement("option");
      o.value = k;
      o.textContent = k + " (" + describe(k) + ")";
      o.selected = k === active;
      select.appendChild(o);
    });
    select.addEventListener("change", () => useRealm(select.value));
    bar.appendChild(select);
    const btn = (text, fn) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "btn";
      b.textContent = text;
      b.addEventListener("click", fn);
      bar.appendChild(b);
    };
    if (token(active)) {
      btn("Sign out", () => logout(active));
    } else {
      btn("Sign in", () => login(active));
      btn("Paste token", () => {
        const v = prompt("Paste an access token for the " + active + " realm");
        if (v) {
          try {
            pasteToken(active, v);
          } catch (e) {
            alert(e.message);
          }
        }
      });
    }
  }

  const ready = completeLogin().catch(() => {}).then(render);
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", render);

  window.SamanvayAuth = { login, logout, pasteToken, token, withAuth, fetch: authFetch, useRealm, activeRealm, ready };
})();
