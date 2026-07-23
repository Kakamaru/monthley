package com.monthley.payment.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import org.springframework.web.bind.annotation.*;

/**
 * Backfill alokasi peringkat line (ADR 0006 P4) — operasi admin.
 *
 * SENGAJA endpoint, bukan runner masa boot: ia menyentuh data kewangan dan
 * patut disengajakan — seseorang memanggilnya, membaca laporan, mengesahkan
 * jumlah. Runner senyap masa boot menyembunyikan masalah.
 *
 * Idempoten — selamat dipanggil berulang. Diguna semula untuk migrasi
 * legacy nanti (per SP, berperingkat).
 */
@RestController
@RequestMapping("/api/v1/admin/allocation-backfill")
class AllocationBackfillController {

    private final AllocationBackfillService backfill;

    AllocationBackfillController(AllocationBackfillService backfill) {
        this.backfill = backfill;
    }

    /** Jalankan backfill untuk SP semasa. Hanya SP_ADMIN. */
    @PostMapping
    AllocationBackfillService.Report run() {
        Access.requireRole("SP_ADMIN", "menjalankan backfill alokasi");
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("SP tak ditetapkan.");
        }
        return backfill.backfill(sp);
    }
}
