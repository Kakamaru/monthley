package com.monthley.notification.internal;

/**
 * Template e-mel — gaya jenama Monthley (navy #122029, lime #bcd634, hijau #16a34a).
 * HTML inline sebab kebanyakan klien e-mel tak sokong <style>.
 */
final class EmailTemplates {

    private EmailTemplates() {}

    private static String shell(String heading, String body, String ctaText, String ctaUrl, String footer) {
        return """
        <!DOCTYPE html>
        <html><body style="margin:0;padding:0;background:#eef2ef;font-family:-apple-system,'Segoe UI',Roboto,Arial,sans-serif">
          <table width="100%%" cellpadding="0" cellspacing="0" style="background:#eef2ef;padding:32px 16px">
            <tr><td align="center">
              <table width="100%%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(18,32,41,.08)">

                <tr><td style="background:#122029;padding:28px 32px">
                  <span style="color:#ffffff;font-size:22px;font-weight:800;letter-spacing:-.5px">
                    Monthley<span style="color:#bcd634">.my</span>
                  </span>
                  <div style="color:#7d939b;font-size:12px;margin-top:4px">The Unified Billing Portal</div>
                </td></tr>

                <tr><td style="padding:36px 32px 8px">
                  <h1 style="margin:0 0 16px;font-size:24px;font-weight:800;color:#16262f;letter-spacing:-.5px">%s</h1>
                  <div style="font-size:15px;line-height:1.65;color:#4a5d64">%s</div>
                </td></tr>

                %s

                <tr><td style="padding:8px 32px 36px">
                  <div style="font-size:13px;line-height:1.6;color:#6b7f86;border-top:1px solid #e6ebe7;padding-top:20px;margin-top:12px">%s</div>
                </td></tr>

                <tr><td style="background:#f4f7f4;padding:20px 32px;text-align:center">
                  <div style="font-size:12px;color:#6b7f86">
                    © 2026 Monthley.my · Rapidevelop Technology Sdn. Bhd.
                  </div>
                </td></tr>

              </table>
            </td></tr>
          </table>
        </body></html>
        """.formatted(heading, body, ctaBlock(ctaText, ctaUrl), footer);
    }

    private static String ctaBlock(String text, String url) {
        if (text == null || url == null) return "";
        return """
        <tr><td style="padding:24px 32px 8px">
          <a href="%s" style="display:inline-block;background:#16a34a;color:#ffffff;text-decoration:none;
             font-size:15px;font-weight:700;padding:14px 32px;border-radius:10px">%s</a>
          <div style="font-size:12px;color:#6b7f86;margin-top:16px;word-break:break-all">
            Atau salin pautan ini:<br>
            <span style="color:#16a34a">%s</span>
          </div>
        </td></tr>
        """.formatted(url, text, url);
    }

    static String verification(String name, String url) {
        return shell(
            "Sahkan e-mel anda",
            "Hai <strong>" + esc(name) + "</strong>,<br><br>"
            + "Terima kasih kerana mendaftar dengan Monthley. Klik butang di bawah "
            + "untuk mengesahkan e-mel anda dan mengaktifkan akaun.",
            "Sahkan E-mel", url,
            "Pautan ini sah selama <strong>24 jam</strong>. "
            + "Jika anda tidak mendaftar dengan Monthley, abaikan e-mel ini.");
    }

    static String passwordReset(String name, String url) {
        return shell(
            "Reset kata laluan",
            "Hai <strong>" + esc(name) + "</strong>,<br><br>"
            + "Kami terima permintaan untuk reset kata laluan akaun Monthley anda. "
            + "Klik butang di bawah untuk tetapkan kata laluan baharu.",
            "Reset Kata Laluan", url,
            "Pautan ini sah selama <strong>1 jam</strong>. "
            + "Jika anda tidak membuat permintaan ini, abaikan e-mel ini — "
            + "kata laluan anda kekal tidak berubah.");
    }

    static String welcome(String name, String url) {
        return shell(
            "Selamat datang ke Monthley 🎉",
            "Hai <strong>" + esc(name) + "</strong>,<br><br>"
            + "E-mel anda telah disahkan dan akaun anda kini aktif.<br><br>"
            + "<strong>Langkah seterusnya:</strong><br>"
            + "• Jika organisasi anda menggunakan Monthley, berikan e-mel ini "
            + "kepada mereka untuk memautkan akaun anda.<br>"
            + "• Setelah dipautkan, bil &amp; sejarah bayaran anda akan muncul di portal.",
            "Buka Portal", url,
            "Ada soalan? Balas e-mel ini dan kami akan bantu.");
    }

    static String invitation(String spName, String registerUrl) {
        return shell(
            "Jemputan ke Monthley",
            "<p>Anda telah dijemput oleh <b>" + esc(spName) + "</b> untuk mengurus akaun bil anda di Monthley.</p>"
            + "<p>Daftar dengan e-mel ini untuk memaut akaun anda secara automatik.</p>",
            "Daftar Sekarang",
            registerUrl,
            "Jika anda tidak mengenali jemputan ini, abaikan e-mel ini.");
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
