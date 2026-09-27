/*
 * Dev sign-in tools (served only under the dev/demo profiles, see
 * DevSignInResources): labels the sign-in bar as the local Keycloak and adds
 * a "Paste token" button for scripted demos.
 */
(function () {
  window.SamanvayAuthDev = {
    decorate(bar, ctx) {
      const label = bar.querySelector("span");
      if (label) label.textContent = "Dev sign-in (local Keycloak) - acting as:";
      if (ctx.signedIn) return;
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
