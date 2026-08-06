package com.monthley.statement.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import com.monthley.statement.api.MonthlyStatsPort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Statistik bulanan — data + SVG untuk skrin, PDF untuk cetakan.
 *
 * Carta dijana SEKALI sebagai SVG dan digunakan kedua-dua tempat.
 * Melukisnya di frontend untuk skrin dan sekali lagi di backend untuk
 * PDF bermakna dua pelaksanaan yang mesti dipadankan.
 */
@RestController
@RequestMapping("/api/v1/reports/monthly-stats")
class MonthlyStatsController {

    private final MonthlyStatsPort stats;
    private final StatementQuery query;
    private final TemplatePdfWriter pdf;

    MonthlyStatsController(MonthlyStatsPort stats, StatementQuery query,
                           TemplatePdfWriter pdf) {
        this.stats = stats;
        this.query = query;
        this.pdf = pdf;
    }

    record Response(MonthlyStatsPort.Stats stats,
                    String dailySvg, String splitSvg,
                    String trendSvg, String paymentSvg, String productSvg) {}

    @GetMapping
    Response data(@RequestParam int year, @RequestParam int month) {
        Access.requireAnyRole("melihat statistik bulanan", "SP_ADMIN", "CLERK", "VIEWER");
        var s = stats.monthly(sp(), year, month);
        return new Response(s,
                ChartSvg.dailyArea(s.daily(), "mst-daily"),
                ChartSvg.splitBars(s.billed(), s.collectedThisPeriod(),
                                   s.collectedArrears()),
                ChartSvg.trendBars(s.trend()),
                ChartSvg.bars3d(s.byPaymentType()),
                ChartSvg.donut(s.byProduct()));
    }

    @GetMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> asPdf(@RequestParam int year, @RequestParam int month) {
        Access.requireAnyRole("mencetak statistik bulanan", "SP_ADMIN", "CLERK", "VIEWER");

        var s = stats.monthly(sp(), year, month);
        var h = query.headerSp(sp());

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("s", s);
        vars.put("h", h);
        vars.put("fmt", new StatementFormatter(h.language(), h.dateFormat()));
        // Delta dikira DI SINI, bukan dalam templat.
        //
        // Percubaan pertama meletakkan subtract/multiply/divide dalam
        // SpEL: BigDecimal.valueOf(100) samar di sana, dan seluruh PDF
        // gagal dengan 500. Aritmetik dalam templat juga tidak boleh
        // diuji.
        vars.put("deltaBil", delta(s.billed(), s.billedPrevious()));
        vars.put("deltaKutipan", delta(s.collected(), s.collectedPrevious()));

        vars.put("dailySvg", ChartSvg.dailyArea(s.daily(), "pdf-daily"));
        vars.put("splitSvg", ChartSvg.splitBars(s.billed(),
                s.collectedThisPeriod(), s.collectedArrears()));
        vars.put("trendSvg", ChartSvg.trendBars(s.trend()));
        vars.put("paymentSvg", ChartSvg.bars3d(s.byPaymentType()));
        vars.put("productSvg", ChartSvg.donut(s.byProduct()));

        byte[] bytes = pdf.render("statement/monthly-stats", vars);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename("statistik-" + year + "-" + month + ".pdf",
                                          StandardCharsets.UTF_8)
                                .build().toString())
                .body(bytes);
    }

    /** '+12% dari bulan lepas', atau kosong jika tiada asas banding. */
    private static String delta(java.math.BigDecimal kini,
                                java.math.BigDecimal lepas) {
        if (lepas == null || lepas.signum() == 0) return "";
        java.math.BigDecimal beza = kini.subtract(lepas);
        java.math.BigDecimal pct = beza.abs()
                .multiply(new java.math.BigDecimal("100"))
                .divide(lepas.abs(), 0, java.math.RoundingMode.HALF_UP);
        return (beza.signum() >= 0 ? "+" : "-") + pct + "% dari bulan lepas";
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
