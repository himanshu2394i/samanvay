package in.samanvay.departments.agriculture;

import org.springframework.web.util.HtmlUtils;

/**
 * The department's citizen sign-in pages, server-rendered with no scripts and no external requests.
 *
 * <p>Design read: a public-sector service page for citizens: trust first, calm, one accent colour per department, one corner
 * radius, plain words, labels above fields, errors next to the form, visible keyboard focus, light and dark from the system
 * setting, and no motion beyond a small press effect that switches off under reduced motion. Native CSS only (a page this small
 * has no use for a framework, and a department login must not call out to a CDN).
 */
final class LoginPages {

    /** What makes one department's page its own: its name and its single accent colour (light and dark mode). */
    record Brand(String name, String shortName, String initial, String accent, String accentDark, String onAccentDark) {}

    /** The three values that must travel through both steps (see docs/contracts/login-assertion.md). */
    record Request(String returnTo, String state, String nonce) {}

    private final Brand brand;
    private final String demoHint;

    LoginPages(Brand brand, String demoHint) {
        this.brand = brand;
        this.demoHint = demoHint;
    }

    /** Step one: mobile number and password. */
    String passwordPage(Request req, String error) {
        return page("Sign in", "Use the mobile number registered with " + brand.shortName() + ".", error,
                "<form method=\"post\" action=\"/login\" novalidate>"
                        + field("mobile", "Registered mobile number", "text", "username", "tel", "10 digits, without +91", "[0-9]{10}", 10, true)
                        + field("password", "Password", "password", "current-password", null, null, null, 0, false)
                        + hidden(req)
                        + "<button type=\"submit\">Continue</button></form>");
    }

    /** Step two: the one-time code. {@code maskedMobile} is only for display, so it is escaped like everything else. */
    String codePage(Request req, String ticket, String maskedMobile, String error) {
        return page("Enter your one-time code", "Enter the 6 digit code sent to the mobile number " + maskedMobile + ".", error,
                "<form method=\"post\" action=\"/login/verify\" novalidate>"
                        + field("code", "One-time code", "text", "one-time-code", "numeric", "6 digits", "[0-9]{6}", 6, true)
                        + "<input type=\"hidden\" name=\"ticket\" value=\"" + e(ticket) + "\">"
                        + "<input type=\"hidden\" name=\"masked\" value=\"" + e(maskedMobile) + "\">"
                        + hidden(req)
                        + "<button type=\"submit\">Verify and continue</button></form>"
                        + "<p class=\"back\"><a href=\"/login?return_to=" + url(req.returnTo()) + "&amp;state=" + url(req.state()) + "&amp;nonce="
                        + url(req.nonce()) + "\">Use a different mobile number</a></p>");
    }

    /** Shown for a request that must never be acted on (bad return address, missing state or nonce). */
    static String invalidRequest() {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Sign in</title></head><body><p>This sign in link is not valid. Go back to Samanvay and start again.</p></body></html>";
    }

    private String page(String heading, String lead, String error, String formHtml) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light dark\">"
                + "<title>" + e(brand.name()) + ": " + e(heading) + "</title><style>" + css() + "</style></head><body>"
                + "<header class=\"bar\"><div class=\"wrap bar-in\"><span class=\"mark\" aria-hidden=\"true\">" + e(brand.initial()) + "</span>"
                + "<div><p class=\"org\">" + e(brand.name()) + "</p><p class=\"sub\">Citizen sign in</p></div></div></header>"
                + "<main class=\"wrap layout\"><section class=\"panel\" aria-labelledby=\"h\">"
                + "<h1 id=\"h\">" + e(heading) + "</h1><p class=\"lead\">" + e(lead) + "</p>"
                + (error == null ? "" : "<p class=\"error\" role=\"alert\">" + e(error) + "</p>")
                + (demoHint == null || demoHint.isBlank() ? "" : "<p class=\"demo\" role=\"note\">" + e(demoHint) + "</p>")
                + formHtml + "</section>"
                + "<aside class=\"note\" aria-labelledby=\"n\"><h2 id=\"n\">What happens when you sign in</h2><ul>"
                + "<li>You sign in here, with the details " + e(brand.shortName()) + " already holds for you.</li>"
                + "<li>" + e(cap(brand.shortName())) + " then tells Samanvay which person you are. Samanvay never sees your password.</li>"
                + "<li>Nothing is shared with any other department until you give your consent in Samanvay.</li></ul></aside>"
                + "</main><footer class=\"wrap foot\">Demonstration service with fake data. Not a real government system.</footer></body></html>";
    }

    private static String field(String name, String label, String type, String autocomplete, String inputmode, String hint, String pattern,
            int maxlength, boolean focus) {
        return "<div class=\"field\"><label for=\"" + name + "\">" + e(label) + "</label>"
                + (hint == null ? "" : "<p class=\"help\" id=\"" + name + "-h\">" + e(hint) + "</p>")
                + "<input id=\"" + name + "\" name=\"" + name + "\" type=\"" + type + "\" autocomplete=\"" + autocomplete + "\""
                + (inputmode == null ? "" : " inputmode=\"" + inputmode + "\"")
                + (pattern == null ? "" : " pattern=\"" + pattern + "\"")
                + (maxlength > 0 ? " maxlength=\"" + maxlength + "\"" : "")
                + (hint == null ? "" : " aria-describedby=\"" + name + "-h\"")
                + " required spellcheck=\"false\" autocapitalize=\"off\"" + (focus ? " autofocus" : "") + "></div>";
    }

    private static String hidden(Request r) {
        return "<input type=\"hidden\" name=\"return_to\" value=\"" + e(r.returnTo()) + "\">"
                + "<input type=\"hidden\" name=\"state\" value=\"" + e(r.state()) + "\">"
                + "<input type=\"hidden\" name=\"nonce\" value=\"" + e(r.nonce()) + "\">";
    }

    private static String cap(String v) {
        return v.isEmpty() ? v : Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    private static String e(String v) {
        return HtmlUtils.htmlEscape(v == null ? "" : v);
    }

    private static String url(String v) {
        return e(java.net.URLEncoder.encode(v == null ? "" : v, java.nio.charset.StandardCharsets.UTF_8));
    }

    private String css() {
        return ":root{--bg:#f3f5f6;--surface:#fff;--ink:#14202b;--muted:#44525e;--line:#c9d3da;--field:#5f717e;--accent:" + brand.accent()
                + ";--on-accent:#fff;--danger:#a4161a;--demo-bg:#fff4d6;--demo-ink:#4a3500;--focus:#0b57d0;--r:6px}"
                + "@media (prefers-color-scheme: dark){:root{--bg:#0e151b;--surface:#16202a;--ink:#e9eff3;--muted:#a8b7c3;--line:#2f3f4b;--field:#8497a5;"
                + "--accent:" + brand.accentDark() + ";--on-accent:" + brand.onAccentDark() + ";--danger:#ff8f92;--demo-bg:#2b2410;--demo-ink:#f0dca8;--focus:#8ab4ff}}"
                + "*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}"
                + "body{margin:0;min-height:100dvh;display:flex;flex-direction:column;background:var(--bg);color:var(--ink);"
                + "font:1rem/1.55 system-ui,-apple-system,'Segoe UI',Roboto,'Noto Sans',sans-serif}"
                + ".wrap{width:100%;max-width:64rem;margin:0 auto;padding:0 1rem}"
                + ".bar{background:var(--surface);border-bottom:1px solid var(--line)}"
                + ".bar-in{display:flex;align-items:center;gap:.9rem;min-height:4rem}"
                + ".mark{display:grid;place-items:center;width:2.4rem;height:2.4rem;border-radius:var(--r);background:var(--accent);color:var(--on-accent);font-weight:700;font-size:1.15rem}"
                + ".org{margin:0;font-weight:650;line-height:1.2}.sub{margin:0;color:var(--muted);font-size:.9rem}"
                + ".layout{flex:1;display:grid;gap:2rem;padding-top:2rem;padding-bottom:2rem;align-content:start}"
                + "@media(min-width:56rem){.layout{grid-template-columns:minmax(0,28rem) minmax(0,1fr);gap:4rem;padding-top:3rem}}"
                + ".panel{background:var(--surface);border:1px solid var(--line);border-radius:var(--r);padding:1.5rem}"
                + "h1{margin:0 0 .4rem;font-size:1.5rem;line-height:1.25;font-weight:650}"
                + ".lead{margin:0 0 1.25rem;color:var(--muted)}"
                + ".field{margin:0 0 1.1rem}label{display:block;font-weight:600;margin-bottom:.25rem}"
                + ".help{margin:0 0 .35rem;color:var(--muted);font-size:.9rem}"
                + "input:not([type=hidden]){display:block;width:100%;min-height:2.75rem;padding:.55rem .75rem;font:inherit;color:var(--ink);background:var(--surface);"
                + "border:1px solid var(--field);border-radius:var(--r)}"
                + "#code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:1.4rem;letter-spacing:.35em}"
                + "input:focus-visible,button:focus-visible,a:focus-visible{outline:3px solid var(--focus);outline-offset:2px}"
                + "button{display:inline-flex;align-items:center;justify-content:center;width:100%;min-height:2.9rem;padding:.6rem 1.2rem;font:inherit;font-weight:650;"
                + "color:var(--on-accent);background:var(--accent);border:1px solid var(--accent);border-radius:var(--r);cursor:pointer}"
                + "button:active{transform:translateY(1px)}"
                + "@media (prefers-reduced-motion:no-preference){button{transition:transform .12s ease-out}}"
                + ".error{margin:0 0 1rem;padding:.65rem .8rem;border:1px solid var(--danger);border-left-width:5px;border-radius:var(--r);color:var(--danger);font-weight:600}"
                + ".demo{margin:0 0 1.1rem;padding:.65rem .8rem;border-radius:var(--r);background:var(--demo-bg);color:var(--demo-ink);font-size:.92rem}"
                + ".back{margin:1.1rem 0 0}.back a{color:var(--ink)}"
                + ".note h2{margin:0 0 .6rem;font-size:1.05rem}.note ul{margin:0;padding-left:1.1rem;color:var(--muted)}.note li{margin:0 0 .5rem;max-width:42ch}"
                + ".foot{padding-top:1rem;padding-bottom:1.5rem;color:var(--muted);font-size:.85rem}";
    }
}
