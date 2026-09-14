function renderIssuedPapers(records) {
  if (!records || !records.length) {
    return "<p>Department records have not appeared yet.</p>";
  }
  const cards = records
    .map((r) => {
      const rows = (r.fields || [])
        .map((f) => "<tr><th scope=\"row\">" + esc(f.label) + "</th><td>" + esc(f.value) + "</td></tr>")
        .join("");
      const body = rows
        ? "<table class=\"paper-fields\">" + rows + "</table>"
        : "<p>Waiting for this department system.</p>";
      const url = r.liveSystemUrl
        ? "<p class=\"hint\">The live system (not this laptop) is " + esc(r.liveSystem) + ".</p>"
        : "";
      return (
        "<article class=\"paper\">" +
        "<p class=\"paper-sys\">" +
        esc(r.liveSystem) +
        " · " +
        esc(r.issuer) +
        "</p>" +
        "<h3>" +
        esc(r.title) +
        "</h3>" +
        body +
        url +
        "</article>"
      );
    })
    .join("");
  return (
    "<h2>Issued records (fetched now)</h2>" +
    "<p class=\"hint\">These stand in for MahaDBT, Mahabhulekh, Fire e-approval, MPCB/MAITRI. They are <strong>not stored</strong> in Samanvay. Live DigiLocker needs a MeitY partner account — this laptop uses the labelled sandbox.</p>" +
    "<div class=\"papers\">" +
    cards +
    "</div>"
  );
}

async function loadIssuedPapers(ref) {
  return api("/api/applications/" + encodeURIComponent(ref) + "/issued-records");
}

function ensureLocker() {
  if (document.getElementById("lockerDlg")) return;
  const dlg = document.createElement("dialog");
  dlg.id = "lockerDlg";
  dlg.setAttribute("aria-labelledby", "lockerTitle");
  dlg.innerHTML =
    "<form method=\"dialog\" id=\"lockerForm\" action=\"#\">" +
    "<p class=\"locker-brand\">DigiLocker <span>sandbox</span></p>" +
    "<h2 id=\"lockerTitle\">Issued documents</h2>" +
    "<p id=\"lockerBody\"></p>" +
    "<ul id=\"lockerList\" class=\"locker-list\"></ul>" +
    "<p class=\"callout honesty\"><strong>Not live DigiLocker.</strong> Partner API credentials are not on this laptop. Pulling a document here only proves the link with sandbox proof.</p>" +
    "<p id=\"lockerStatus\" class=\"status\" aria-live=\"polite\"></p>" +
    "<div class=\"row\">" +
    "<button type=\"submit\" class=\"btn primary\" id=\"lockerPull\" value=\"ok\">Pull selected issued document</button>" +
    "<button type=\"submit\" class=\"btn\" value=\"cancel\">Cancel</button>" +
    "</div></form>";
  document.body.appendChild(dlg);
}

async function openLocker(dept, deptName, onPull) {
  ensureLocker();
  const dlg = document.getElementById("lockerDlg");
  const list = document.getElementById("lockerList");
  const body = document.getElementById("lockerBody");
  body.textContent = "Issued documents the " + deptName + " system would place in DigiLocker.";
  list.innerHTML = "<li>Reading issued documents…</li>";
  dlg.showModal();
  try {
    const docs = await api("/api/connector/issued-documents?departmentCode=" + encodeURIComponent(dept));
    if (!docs || !docs.length) {
      list.innerHTML = "<li>No issued documents mapped for this department in the sandbox.</li>";
      return;
    }
    list.innerHTML = docs
      .map((d, i) => {
        return (
          "<li><label class=\"choice\">" +
          "<input type=\"radio\" name=\"lockerDoc\" value=\"" +
          esc(d.id) +
          "\"" +
          (i === 0 ? " checked" : "") +
          "/> <strong>" +
          esc(d.title) +
          "</strong><br/><span>" +
          esc(d.issuer) +
          " · " +
          esc(d.liveSystem) +
          "</span></label></li>"
        );
      })
      .join("");
  } catch (e) {
    list.innerHTML = "";
    body.textContent = friendly(e);
  }
  const form = document.getElementById("lockerForm");
  form.onsubmit = async (event) => {
    const submitter = event.submitter;
    if (!submitter || submitter.value === "cancel") {
      return;
    }
    event.preventDefault();
    const btn = document.getElementById("lockerPull");
    setBusy(btn, true, "Pulling…");
    try {
      await onPull();
      dlg.close();
    } catch (e) {
      setStatus(document.getElementById("lockerStatus"), "bad", friendly(e));
    } finally {
      setBusy(btn, false);
    }
  };
}
