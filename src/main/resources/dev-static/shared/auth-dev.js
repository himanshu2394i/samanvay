/*
 * Dev sign-in tools (served only under the dev/demo profiles, see
 * DevSignInResources): labels the sign-in bar as the local Keycloak and adds
 * a one-click demo sign-in (no password/OTP, for judges) plus a "Paste token"
 * button for scripted demos.
 */
(function () {
  window.SamanvayAuthDev = {
    decorate(bar, ctx) {
      const label = bar.querySelector("span");
      if (label) label.textContent = "Dev sign-in (local Keycloak) - acting as:";
      if (ctx.signedIn) return;
      // One-click demo sign-in: mints a token from /ui/demo-signin (demo profile only), no OTP.
      // Citizen pages sign in as a fresh demo-citizen (nothing linked yet); staff pages as demo-admin.
      ctx.btn("Demo sign-in (no OTP)", () => {
        const role = ctx.realm === "staff" ? "admin" : "citizen";
        fetch("/ui/demo-signin", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ role: role }),
        })
          .then(function (r) {
            if (!r.ok) throw new Error("demo sign-in unavailable");
            return r.json();
          })
          .then(function (b) {
            ctx.storeToken(ctx.realm, b.access_token);
          })
          .catch(function () {
            alert("Demo sign-in is not available in this build.");
          });
      });
      ctx.btn("Paste token", () => {
        const v = prompt("Paste an access token for the " + ctx.realm + " realm");
        if (!v) return;
        const t = v.trim().replace(/^Bearer\s+/i, "");
        if (!ctx.claims(t)) {
          alert("That does not look like a JWT access token.");
          return;
        }
        ctx.storeToken(ctx.realm, t);
      });
    },
  };
})();
