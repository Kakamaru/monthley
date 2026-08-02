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

    /**
     * Resit — PAUTAN, bukan lampiran.
     *
     * Butiran utama dalam badan e-mel supaya pelanggan boleh mengesahkan
     * bayaran tanpa mengklik. Pautan untuk resit penuh.
     */
    static String receipt(String name, String spName, String receiptNo,
                          String amount, String tarikh, String url) {
        return shell(
            "Resit Bayaran",
            "Hai <strong>" + esc(name) + "</strong>,<br><br>"
            + "Terima kasih. Bayaran anda kepada <strong>" + esc(spName)
            + "</strong> telah diterima.<br><br>"
            + "<table style=\"font-size:14px;line-height:1.8\">"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">No. Resit</td>"
            + "<td><strong>" + esc(receiptNo) + "</strong></td></tr>"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">Tarikh</td>"
            + "<td>" + esc(tarikh) + "</td></tr>"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">Jumlah</td>"
            + "<td><strong>" + esc(amount) + "</strong></td></tr>"
            + "</table>",
            "Lihat Resit", url,
            "Simpan e-mel ini sebagai rujukan. Pautan di atas kekal sah.");
    }

    /**
     * Penyata akaun kepada pelanggan.
     *
     * Baki dipaparkan supaya pelanggan tahu keadaannya tanpa membuka
     * pautan — tetapi dengan tarikh, kerana ia menjadi basi sebaik dia
     * membayar.
     */
    static String statement(String name, String spName, String accountNo,
                            String tempoh, String baki,
                            String spEmail, String spPhone, String url) {
        // Baris hubungan DISEMBUNYIKAN kalau kosong. Legacy memaparkan
        // 'Email:' dan 'Telephone:' tanpa nilai apabila SP tidak
        // mengisinya — pelanggan melihat label yang menjanjikan sesuatu
        // yang tiada.
        String hubungan = "";
        if ((spEmail != null && !spEmail.isBlank())
                || (spPhone != null && !spPhone.isBlank())) {
            hubungan = "<br>Untuk pertanyaan penyata, hubungi <strong>"
                    + esc(spName) + "</strong>";
            if (spEmail != null && !spEmail.isBlank()) {
                hubungan += " di " + esc(spEmail);
            }
            if (spPhone != null && !spPhone.isBlank()) {
                hubungan += (spEmail != null && !spEmail.isBlank() ? " atau " : " di ")
                        + esc(spPhone);
            }
            hubungan += ".";
        }

        return shell(
            "Penyata Akaun",
            "Hai <strong>" + esc(name) + "</strong>,<br><br>"
            + "Penyata akaun anda daripada <strong>" + esc(spName)
            + "</strong> untuk " + esc(tempoh) + " sudah sedia.<br><br>"
            + "<table style=\"font-size:14px;line-height:1.8\">"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">No. Akaun</td>"
            + "<td><strong>" + esc(accountNo) + "</strong></td></tr>"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">Tempoh</td>"
            + "<td>" + esc(tempoh) + "</td></tr>"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">Baki semasa</td>"
            + "<td><strong>" + esc(baki) + "</strong></td></tr>"
            + "</table>",
            "Lihat Penyata", url,
            "Baki di atas adalah pada tarikh e-mel ini dihantar. Pautan "
            + "sentiasa menunjukkan keadaan terkini dan kekal sah."
            + hubungan);
    }

    /**
     * Laporan penjanaan bil kepada admin SP.
     *
     * TIADA butang. Laporan ini maklumat, bukan tindakan — admin yang
     * mahu melihat invois membuka skrin Dokumen Kewangan, dan pautan
     * ke situ memerlukan log masuk yang e-mel tidak boleh andaikan.
     *
     * Tempoh disenaraikan kerana ia SELALUNYA bukan bulan larian:
     * POSTPAID pada Julai membilkan Jun, dan akaun tahunan boleh
     * menghasilkan dua belas tempoh dalam satu larian.
     */
    static String generationReport(String spName, String tarikh,
                                   int akaunDiimbas, int invoisDikeluarkan,
                                   String jumlah, java.util.List<String> tempoh) {
        String senarai = (tempoh == null || tempoh.isEmpty())
                ? "<em>tiada</em>"
                : tempoh.stream().map(EmailTemplates::esc)
                        .reduce((a, b) -> a + ", " + b).orElse("");

        return shell(
            "Laporan Penjanaan Bil",
            "Bil untuk <strong>" + esc(spName) + "</strong> telah dijana pada "
            + esc(tarikh) + ".<br><br>"
            + "<table style=\"font-size:14px;line-height:1.8\">"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">Akaun diimbas</td>"
            + "<td><strong>" + akaunDiimbas + "</strong></td></tr>"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">Invois dikeluarkan</td>"
            + "<td><strong>" + invoisDikeluarkan + "</strong></td></tr>"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">Jumlah</td>"
            + "<td><strong>" + esc(jumlah) + "</strong></td></tr>"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">Tempoh dibil</td>"
            + "<td>" + senarai + "</td></tr>"
            + "</table>",
            null, null,
            "Penyata akan dihantar kepada pelanggan secara berasingan.");
    }

    /**
     * Dokumen dihantar semula — resit atau invois.
     *
     * Ayat pembuka berbeza mengikut jenis: resit mengesahkan bayaran
     * DITERIMA, invois memberitahu ada jumlah yang PERLU DIBAYAR.
     * Menghantar 'Terima kasih, bayaran anda telah diterima' bersama
     * invois akan mengelirukan pelanggan sepenuhnya.
     */
    static String document(String name, String spName, String docLabel,
                           String docNo, String amount, String tarikh, String url) {
        boolean resit = docLabel != null
                && docLabel.toLowerCase().contains("resit");

        String pembuka = resit
                ? "Berikut ialah salinan resit bayaran anda kepada <strong>"
                  + esc(spName) + "</strong>."
                : "Berikut ialah salinan invois anda daripada <strong>"
                  + esc(spName) + "</strong>.";

        return shell(
            esc(docLabel),
            "Hai <strong>" + esc(name) + "</strong>,<br><br>"
            + pembuka + "<br><br>"
            + "<table style=\"font-size:14px;line-height:1.8\">"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">No. Dokumen</td>"
            + "<td><strong>" + esc(docNo) + "</strong></td></tr>"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">Tarikh</td>"
            + "<td>" + esc(tarikh) + "</td></tr>"
            + "<tr><td style=\"padding-right:16px;color:#6b7f86\">Jumlah</td>"
            + "<td><strong>" + esc(amount) + "</strong></td></tr>"
            + "</table>",
            "Lihat " + esc(docLabel), url,
            "Simpan e-mel ini sebagai rujukan. Pautan di atas kekal sah.");
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
