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
    "<p class=\"hint\">These stand in for MahaDBT, Mahabhulekh, Fire e-approval, MPCB/MAITRI. They are <strong>not stored</strong> in Samanvay.</p>" +
    "<div class=\"papers\">" +
    cards +
    "</div>"
  );
}

async function loadIssuedPapers(ref) {
  return api("/api/applications/" + encodeURIComponent(ref) + "/issued-records");
}
