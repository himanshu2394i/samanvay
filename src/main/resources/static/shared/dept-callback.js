/*
 * The page a department sends the citizen back to after its own login (docs/contracts/login-assertion.md).
 * It completes the link through SamanvayDeptLogin and then returns the citizen to where they were; on any failure it says
 * so plainly and offers a way back. Kept in its own file (no inline script) so a Content-Security-Policy can be added later.
 */
(async function () {
  const msg = document.getElementById("msg");
  try {
    await window.SamanvayAuth.ready;
    const out = await window.SamanvayDeptLogin.complete(window.location.search);
    msg.textContent = "Your department account is linked. Taking you back…";
    window.location.replace(out.returnPath);
  } catch (e) {
    msg.className = "bad";
    msg.setAttribute("role", "alert");
    msg.textContent = e && e.message ? e.message : "Something went wrong.";
    document.getElementById("back").hidden = false;
  }
})();
