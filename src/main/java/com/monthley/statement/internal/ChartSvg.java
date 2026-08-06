package com.monthley.statement.internal;

import com.monthley.statement.api.MonthlyStatsPort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Carta sebagai SVG, dijana di backend.
 *
 * SATU pelaksanaan untuk skrin DAN PDF. Melukisnya dua kali — sekali
 * dengan pustaka carta di frontend, sekali lagi untuk PDF — bermakna
 * dua kod yang mesti dipadankan, dan yang kedua akan menyimpang.
 *
 * openhtmltopdf merender SVG terbenam, jadi PDF mendapat carta yang
 * SAMA PERSIS seperti skrin.
 *
 * XHTML KETAT: tiada entiti bernama. Aksara Unicode terus sahaja.
 */
final class ChartSvg {

    private ChartSvg() {}

    private static final String[] WARNA = {
            "#2f7d32", "#e67e22", "#2980b9", "#8e44ad", "#c0392b",
            "#16a085", "#d4ac0d", "#7f8c8d", "#27ae60", "#2c3e50" };

    /** Bar berkembar: dibil lawan dikutip, dua belas bulan. */
    static String trendBars(List<MonthlyStatsPort.MonthPoint> data) {
        if (data.isEmpty()) return "";

        int w = 760, h = 260, kiri = 58, bawah = 34, atas = 16;
        double maks = 0;
        for (var d : data) {
            maks = Math.max(maks, d.billed().doubleValue());
            maks = Math.max(maks, d.collected().doubleValue());
        }
        if (maks <= 0) maks = 1;

        double plotW = w - kiri - 12, plotH = h - bawah - atas;
        double lebarKumpulan = plotW / data.size();
        double lebarBar = Math.min(14, lebarKumpulan / 2.6);

        StringBuilder s = new StringBuilder();
        s.append("<svg viewBox=\"0 0 ").append(w).append(' ').append(h)
         .append("\" xmlns=\"http://www.w3.org/2000/svg\" ")
         .append("style=\"width:100%;height:auto\">");

        // Garis grid dan label paksi
        for (int i = 0; i <= 4; i++) {
            double y = atas + plotH - (plotH * i / 4.0);
            s.append("<line x1=\"").append(kiri).append("\" y1=\"").append(fmt(y))
             .append("\" x2=\"").append(w - 12).append("\" y2=\"").append(fmt(y))
             .append("\" stroke=\"#ddd\" stroke-width=\"0.6\"/>");
            s.append("<text x=\"").append(kiri - 6).append("\" y=\"").append(fmt(y + 3))
             .append("\" font-size=\"11\" fill=\"#666\" text-anchor=\"end\">")
             .append(ringkas(maks * i / 4.0)).append("</text>");
        }

        for (int i = 0; i < data.size(); i++) {
            var d = data.get(i);
            double xTengah = kiri + lebarKumpulan * (i + 0.5);

            double hB = plotH * d.billed().doubleValue() / maks;
            double hC = plotH * d.collected().doubleValue() / maks;

            // Bar dibil DAHULU: bar dikutip berada di hadapannya, dan
            // kedalaman yang bertindih mesti menutup yang di belakang.
            s.append(bar3d(xTengah - lebarBar - 1, atas + plotH - hB,
                           lebarBar, Math.max(hB, 0), "#93a5b1", 4));
            s.append(bar3d(xTengah + 1, atas + plotH - hC,
                           lebarBar, Math.max(hC, 0), "#2f7d32", 4));

            s.append("<text x=\"").append(fmt(xTengah)).append("\" y=\"")
             .append(h - bawah + 14)
             .append("\" font-size=\"10.5\" fill=\"#444\" text-anchor=\"middle\">")
             .append(esc(d.label())).append("</text>");
        }

        // Legenda
        s.append("<rect x=\"").append(kiri).append("\" y=\"").append(h - 12)
         .append("\" width=\"9\" height=\"9\" fill=\"#93a5b1\"/>")
         .append("<text x=\"").append(kiri + 13).append("\" y=\"").append(h - 4)
         .append("\" font-size=\"11\" fill=\"#444\">Dibil</text>")
         .append("<rect x=\"").append(kiri + 58).append("\" y=\"").append(h - 12)
         .append("\" width=\"9\" height=\"9\" fill=\"#2f7d32\"/>")
         .append("<text x=\"").append(kiri + 71).append("\" y=\"").append(h - 4)
         .append("\" font-size=\"11\" fill=\"#444\">Dikutip</text>");

        return s.append("</svg>").toString();
    }

    /**
     * Kutipan harian TERKUMPUL sebagai garis berlorek.
     *
     * Terkumpul kerana garis yang sentiasa menaik menunjukkan RENTAK
     * kutipan: bila ia mendatar, kutipan berhenti. Bar harian sahaja
     * terlalu bergerigi untuk membaca corak itu.
     *
     * Kecerunan diberi id UNIK setiap panggilan — dua SVG dalam satu
     * halaman dengan id sama menyebabkan yang kedua mengambil kecerunan
     * yang pertama.
     */
    static String dailyArea(List<MonthlyStatsPort.DayPoint> data, String id) {
        if (data.isEmpty()) return "";

        double maks = 0;
        for (var d : data) maks = Math.max(maks, d.cumulative().doubleValue());
        if (maks <= 0) maks = 1;

        int w = 640, h = 200, kiri = 8, kanan = 8, atas = 12, bawah = 8;
        double plotW = w - kiri - kanan, plotH = h - atas - bawah;

        StringBuilder garis = new StringBuilder();
        StringBuilder kawasan = new StringBuilder();

        for (int i = 0; i < data.size(); i++) {
            double x = kiri + (data.size() == 1 ? plotW / 2
                    : plotW * i / (data.size() - 1.0));
            double y = atas + plotH
                    - plotH * data.get(i).cumulative().doubleValue() / maks;
            garis.append(i == 0 ? "M " : " L ").append(fmt(x)).append(' ').append(fmt(y));
        }

        kawasan.append(garis).append(" L ").append(fmt(w - kanan)).append(' ')
               .append(fmt(atas + plotH)).append(" L ").append(fmt(kiri)).append(' ')
               .append(fmt(atas + plotH)).append(" Z");

        // Titik hujung: di mana kutipan berdiri hari ini.
        var akhir = data.get(data.size() - 1);
        double xA = kiri + plotW;
        double yA = atas + plotH - plotH * akhir.cumulative().doubleValue() / maks;

        return "<svg viewBox=\"0 0 " + w + " " + h + "\" "
             + "xmlns=\"http://www.w3.org/2000/svg\" "
             + "style=\"width:100%;height:auto;display:block\">"
             + "<defs><linearGradient id=\"" + id + "\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">"
             + "<stop offset=\"0%\" stop-color=\"#2f7d32\" stop-opacity=\"0.34\"/>"
             + "<stop offset=\"100%\" stop-color=\"#2f7d32\" stop-opacity=\"0\"/>"
             + "</linearGradient></defs>"
             + "<path d=\"" + kawasan + "\" fill=\"url(#" + id + ")\"/>"
             + "<path d=\"" + garis + "\" fill=\"none\" stroke=\"#2f7d32\" "
             + "stroke-width=\"2.4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
             + "<circle cx=\"" + fmt(xA) + "\" cy=\"" + fmt(yA)
             + "\" r=\"9\" fill=\"#2f7d32\" opacity=\"0.24\"/>"
             + "<circle cx=\"" + fmt(xA) + "\" cy=\"" + fmt(yA)
             + "\" r=\"4.5\" fill=\"#2f7d32\"/>"
             + "</svg>";
    }

    /**
     * Tiga bar menegak: dibil, dikutip untuk tempoh ini, dikutip untuk
     * tunggakan lama.
     *
     * Menjawab soalan yang laporan legacy tinggalkan terbuka — mengapa
     * kutipan boleh melebihi bil.
     */
    static String splitBars(BigDecimal billed, BigDecimal thisPeriod,
                            BigDecimal arrears) {
        double[] nilai = { billed.doubleValue(), thisPeriod.doubleValue(),
                           arrears.doubleValue() };
        String[] nama = { "Invois Dijana", "Kutipan Tempoh Ini", "Kutipan Tunggakan" };
        String[] warna = { "#7f8c8d", "#2f7d32", "#e67e22" };

        double maks = 0;
        for (double v : nilai) maks = Math.max(maks, v);
        if (maks <= 0) return "";

        int w = 460, h = 250, atas = 30, bawah = 52;
        double plotH = h - atas - bawah, dalam = 7;
        double lebar = 74, jarak = w / 3.0;

        StringBuilder s = new StringBuilder();
        s.append("<svg viewBox=\"0 0 ").append(w).append(' ').append(h)
         .append("\" xmlns=\"http://www.w3.org/2000/svg\" ")
         .append("style=\"width:100%;height:auto;display:block\">");

        for (int i = 0; i < 3; i++) {
            double tinggi = plotH * nilai[i] / maks;
            double x = jarak * i + (jarak - lebar) / 2;
            double y = atas + plotH - tinggi;

            s.append(bar3d(x, y, lebar, tinggi, warna[i], dalam));

            s.append("<text x=\"").append(fmt(x + lebar / 2)).append("\" y=\"")
             .append(fmt(y - dalam - 8))
             .append("\" font-size=\"13\" font-weight=\"bold\" fill=\"#333\" ")
             .append("text-anchor=\"middle\">").append(wang(nilai[i])).append("</text>");

            s.append("<text x=\"").append(fmt(x + lebar / 2)).append("\" y=\"")
             .append(atas + plotH + 20)
             .append("\" font-size=\"11\" fill=\"#555\" text-anchor=\"middle\">")
             .append(nama[i]).append("</text>");
        }

        s.append("<line x1=\"0\" y1=\"").append(fmt(atas + plotH))
         .append("\" x2=\"").append(w).append("\" y2=\"").append(fmt(atas + plotH))
         .append("\" stroke=\"#ddd\" stroke-width=\"1\"/>");

        return s.append("</svg>").toString();
    }

    /**
     * Bar mendatar dengan kesan kedalaman.
     *
     * Lebih baik daripada donut untuk jenis bayaran: nama dibaca terus,
     * dan panjang bar lebih mudah dibandingkan daripada sudut.
     *
     * Kesan 3D menambah kedalaman pada hujung bar, jadi bar kelihatan
     * sedikit lebih panjang daripada nilainya. Label nombor pada setiap
     * bar diperlukan supaya mata tidak bergantung pada panjang sahaja.
     */
    static String bars3d(List<MonthlyStatsPort.Slice> data) {
        if (data.isEmpty()) return "";

        double maks = 0;
        for (var d : data) maks = Math.max(maks, d.amount().doubleValue());
        if (maks <= 0) return "";

        // LEBAR viewBox menentukan saiz teks yang DILIHAT.
        //
        // Carta ini duduk dalam kotak separuh lebar. Pada 760 ia
        // dikecilkan lebih separuh, dan teks 11px menjadi 6px di skrin —
        // lebih kecil daripada carta 12-bulan bersebelahannya.
        //
        // 420 memberi skala yang hampir sama dengan carta penuh lebar.
        int kiri = 96, kanan = 22, atas = 26;
        double tinggiBar = 26, jurang = 16, dalam = 7;
        int h = (int) (atas + data.size() * (tinggiBar + jurang) + 30);
        int w = 420;
        double plotW = w - kiri - kanan - dalam;

        StringBuilder s = new StringBuilder();
        s.append("<svg viewBox=\"0 0 ").append(w).append(' ').append(h)
         .append("\" xmlns=\"http://www.w3.org/2000/svg\" ")
         .append("style=\"width:100%;height:auto\">");

        // Grid menegak dan label paksi
        for (int i = 0; i <= 4; i++) {
            double x = kiri + plotW * i / 4.0;
            s.append("<line x1=\"").append(fmt(x)).append("\" y1=\"").append(atas)
             .append("\" x2=\"").append(fmt(x)).append("\" y2=\"").append(h - 30)
             .append("\" stroke=\"#e2e2e2\" stroke-width=\"0.6\" ")
             .append("stroke-dasharray=\"2,2\"/>");
            s.append("<text x=\"").append(fmt(x)).append("\" y=\"").append(atas - 8)
             .append("\" font-size=\"11\" fill=\"#666\" text-anchor=\"middle\">")
             .append(ringkas(maks * i / 4.0)).append("</text>");
        }
        s.append("<text x=\"").append(kiri + plotW / 2).append("\" y=\"").append(atas - 20)
         .append("\" font-size=\"10\" fill=\"#888\" text-anchor=\"middle\">(MYR)</text>");

        double y = atas + 6;
        int i = 0;
        for (var d : data) {
            double nilai = Math.max(d.amount().doubleValue(), 0);
            double lebar = plotW * nilai / maks;
            String warna = WARNA[i % WARNA.length];
            String gelap = gelapkan(warna);

            // Muka atas dan sisi memberi kedalaman
            s.append("<path d=\"M ").append(fmt(kiri)).append(' ').append(fmt(y))
             .append(" L ").append(fmt(kiri + dalam)).append(' ').append(fmt(y - dalam))
             .append(" L ").append(fmt(kiri + lebar + dalam)).append(' ').append(fmt(y - dalam))
             .append(" L ").append(fmt(kiri + lebar)).append(' ').append(fmt(y))
             .append(" Z\" fill=\"").append(gelap).append("\"/>");

            s.append("<path d=\"M ").append(fmt(kiri + lebar)).append(' ').append(fmt(y))
             .append(" L ").append(fmt(kiri + lebar + dalam)).append(' ').append(fmt(y - dalam))
             .append(" L ").append(fmt(kiri + lebar + dalam)).append(' ')
             .append(fmt(y - dalam + tinggiBar))
             .append(" L ").append(fmt(kiri + lebar)).append(' ').append(fmt(y + tinggiBar))
             .append(" Z\" fill=\"").append(gelap).append("\"/>");

            s.append("<rect x=\"").append(fmt(kiri)).append("\" y=\"").append(fmt(y))
             .append("\" width=\"").append(fmt(lebar))
             .append("\" height=\"").append(fmt(tinggiBar))
             .append("\" fill=\"").append(warna).append("\"/>");

            // Nama di kiri
            s.append("<text x=\"").append(kiri - 10).append("\" y=\"")
             .append(fmt(y + tinggiBar / 2 + 4))
             .append("\" font-size=\"12\" fill=\"#333\" text-anchor=\"end\">")
             .append(esc(d.label())).append("</text>");

            // Nilai: DALAM bar jika muat, di luar jika bar terlalu pendek
            boolean dalamBar = lebar > 90;
            s.append("<text x=\"")
             .append(fmt(dalamBar ? kiri + lebar - 10 : kiri + lebar + dalam + 8))
             .append("\" y=\"").append(fmt(y + tinggiBar / 2 + 4))
             .append("\" font-size=\"12\" font-weight=\"bold\" fill=\"")
             .append(dalamBar ? "#fff" : "#333").append("\" text-anchor=\"")
             .append(dalamBar ? "end" : "start").append("\">")
             .append(wang(nilai)).append("</text>");

            y += tinggiBar + jurang;
            i++;
        }

        return s.append("</svg>").toString();
    }

    /** Bar menegak dengan muka atas dan sisi kanan. */
    private static String bar3d(double x, double y, double w, double h,
                                String warna, double dalam) {
        if (h <= 0.5) return "";
        String gelap = gelapkan(warna);
        StringBuilder s = new StringBuilder();

        s.append("<path d=\"M ").append(fmt(x)).append(' ').append(fmt(y))
         .append(" L ").append(fmt(x + dalam)).append(' ').append(fmt(y - dalam))
         .append(" L ").append(fmt(x + w + dalam)).append(' ').append(fmt(y - dalam))
         .append(" L ").append(fmt(x + w)).append(' ').append(fmt(y))
         .append(" Z\" fill=\"").append(gelap).append("\"/>");

        s.append("<path d=\"M ").append(fmt(x + w)).append(' ').append(fmt(y))
         .append(" L ").append(fmt(x + w + dalam)).append(' ').append(fmt(y - dalam))
         .append(" L ").append(fmt(x + w + dalam)).append(' ').append(fmt(y + h - dalam))
         .append(" L ").append(fmt(x + w)).append(' ').append(fmt(y + h))
         .append(" Z\" fill=\"").append(gelap).append("\"/>");

        s.append("<rect x=\"").append(fmt(x)).append("\" y=\"").append(fmt(y))
         .append("\" width=\"").append(fmt(w)).append("\" height=\"").append(fmt(h))
         .append("\" fill=\"").append(warna).append("\"/>");

        return s.toString();
    }

    /** Sisi bar lebih gelap daripada mukanya. */
    private static String gelapkan(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return String.format("#%02x%02x%02x",
                (int) (r * 0.72), (int) (g * 0.72), (int) (b * 0.72));
    }

    /** Donut dengan legenda di sebelah. */
    static String donut(List<MonthlyStatsPort.Slice> data) {
        if (data.isEmpty()) return "";

        double jumlah = 0;
        for (var d : data) jumlah += Math.max(d.amount().doubleValue(), 0);
        if (jumlah <= 0) return "";

        // Sama seperti bars3d: lebar kecil supaya teks tidak mengecil
        // apabila SVG dimuatkan ke dalam kotak separuh lebar.
        int w = 430, h = 190, cx = 82, cy = 95;
        double rLuar = 72, rDalam = 40;

        StringBuilder s = new StringBuilder();
        s.append("<svg viewBox=\"0 0 ").append(w).append(' ').append(h)
         .append("\" xmlns=\"http://www.w3.org/2000/svg\" ")
         .append("style=\"width:100%;height:auto\">");

        double mula = -90;
        int i = 0;
        for (var d : data) {
            double nilai = Math.max(d.amount().doubleValue(), 0);
            if (nilai <= 0) { i++; continue; }
            double sudut = 360 * nilai / jumlah;
            s.append(arc(cx, cy, rLuar, rDalam, mula, mula + sudut, WARNA[i % WARNA.length]));
            mula += sudut;
            i++;
        }

        // Legenda: nama, amaun, peratus
        int y = 22, lx = 176;
        i = 0;
        for (var d : data) {
            double nilai = Math.max(d.amount().doubleValue(), 0);
            double pct = 100 * nilai / jumlah;
            s.append("<rect x=\"").append(lx).append("\" y=\"").append(y - 8)
             .append("\" width=\"9\" height=\"9\" fill=\"")
             .append(WARNA[i % WARNA.length]).append("\"/>");
            s.append("<text x=\"").append(lx + 14).append("\" y=\"").append(y)
             .append("\" font-size=\"12\" fill=\"#333\">").append(esc(d.label()))
             .append("</text>");
            s.append("<text x=\"").append(w - 12).append("\" y=\"").append(y)
             .append("\" font-size=\"12\" fill=\"#333\" text-anchor=\"end\">")
             .append(wang(nilai)).append("  (")
             .append(new BigDecimal(pct).setScale(1, RoundingMode.HALF_UP))
             .append("%)</text>");
            y += 20;
            i++;
            if (y > h - 10) break;
        }

        return s.append("</svg>").toString();
    }

    private static String arc(double cx, double cy, double rL, double rD,
                              double a1, double a2, String warna) {
        // Bulatan penuh tidak boleh dilukis dengan satu arc: titik mula
        // dan tamat bertindih, dan laluan menjadi kosong.
        if (a2 - a1 >= 359.999) {
            return "<path d=\"M " + fmt(cx - rL) + " " + fmt(cy)
                 + " a " + fmt(rL) + " " + fmt(rL) + " 0 1 0 " + fmt(rL * 2) + " 0"
                 + " a " + fmt(rL) + " " + fmt(rL) + " 0 1 0 " + fmt(-rL * 2) + " 0 Z"
                 + " M " + fmt(cx - rD) + " " + fmt(cy)
                 + " a " + fmt(rD) + " " + fmt(rD) + " 0 1 1 " + fmt(rD * 2) + " 0"
                 + " a " + fmt(rD) + " " + fmt(rD) + " 0 1 1 " + fmt(-rD * 2) + " 0 Z\""
                 + " fill=\"" + warna + "\" fill-rule=\"evenodd\"/>";
        }
        double r1 = Math.toRadians(a1), r2 = Math.toRadians(a2);
        double x1 = cx + rL * Math.cos(r1), y1 = cy + rL * Math.sin(r1);
        double x2 = cx + rL * Math.cos(r2), y2 = cy + rL * Math.sin(r2);
        double x3 = cx + rD * Math.cos(r2), y3 = cy + rD * Math.sin(r2);
        double x4 = cx + rD * Math.cos(r1), y4 = cy + rD * Math.sin(r1);
        int besar = (a2 - a1) > 180 ? 1 : 0;

        return "<path d=\"M " + fmt(x1) + " " + fmt(y1)
             + " A " + fmt(rL) + " " + fmt(rL) + " 0 " + besar + " 1 " + fmt(x2) + " " + fmt(y2)
             + " L " + fmt(x3) + " " + fmt(y3)
             + " A " + fmt(rD) + " " + fmt(rD) + " 0 " + besar + " 0 " + fmt(x4) + " " + fmt(y4)
             + " Z\" fill=\"" + warna + "\"/>";
    }

    private static String fmt(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String ringkas(double v) {
        if (v >= 1_000_000) return new BigDecimal(v / 1_000_000)
                .setScale(1, RoundingMode.HALF_UP) + "j";
        if (v >= 1_000) return new BigDecimal(v / 1_000)
                .setScale(0, RoundingMode.HALF_UP) + "k";
        return new BigDecimal(v).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Amaun dengan awalan RM.
     *
     * Dikongsi oleh ketiga-tiga carta: label tanpa mata wang memaksa
     * pembaca mengandaikan, dan laporan yang sama boleh mengandungi
     * kiraan (25 resit) di sebelah amaun (2,955.34).
     */
    private static String wang(double v) {
        return String.format("RM %,.2f", v);
    }

    /** Nama produk ialah data pengguna: satu & mentah mematikan render. */
    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
