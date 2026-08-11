package com.monthley.expenses.internal;

import com.monthley.shared.Access;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Tetapan modul Perbelanjaan.
 *
 *   GET /api/v1/expenses/settings
 *   PUT /api/v1/expenses/settings
 *
 * Tetapan PV duduk di sini dan bukan sp_document_setting: SP yang tidak
 * melanggan modul tidak sepatutnya membawa lajur PV dalam tetapan teras
 * mereka (ADR 0016).
 */
@RestController
@RequestMapping("/api/v1/expenses/settings")
class ExpSettingController {

    private final ExpSettingRepository settings;
    private final ModuleGuard modules;

    ExpSettingController(ExpSettingRepository settings, ModuleGuard modules) {
        this.settings = settings;
        this.modules = modules;
    }

    record SettingDto(boolean sstEnabled, BigDecimal sstRate,
                      String pvPrefix, int pvNoSize, long pvNoStart,
                      String cashPrefix, int cashNoSize, long cashNoStart,
                      Long bankGlAccountId) {
        static SettingDto from(ExpSetting s) {
            return new SettingDto(s.isSstEnabled(), s.getSstRate(),
                    s.getPvPrefix(), s.getPvNoSize(), s.getPvNoStart(),
                    s.getCashPrefix(), s.getCashNoSize(), s.getCashNoStart(),
                    s.getBankGlAccountId());
        }
    }

    @GetMapping
    @Transactional
    SettingDto get() {
        Access.requireAnyRole("melihat tetapan perbelanjaan", "SP_ADMIN", "CLERK");
        String sp = sp();
        return SettingDto.from(settings.findById(sp)
                .orElseGet(() -> settings.save(new ExpSetting(sp))));
    }

    @PutMapping
    @Transactional
    ResponseEntity<?> update(@RequestBody SettingDto r) {
        Access.requireRole("SP_ADMIN", "mengubah tetapan perbelanjaan");
        modules.require(ModuleGuard.PERBELANJAAN, "mengubah tetapan");

        String sp = sp();
        ExpSetting s = settings.findById(sp).orElseGet(() -> settings.save(new ExpSetting(sp)));

        s.setSstEnabled(r.sstEnabled());
        s.setSstRate(r.sstRate() == null ? BigDecimal.ZERO : r.sstRate());
        if (r.pvPrefix() != null && !r.pvPrefix().isBlank()) s.setPvPrefix(r.pvPrefix().trim());
        if (r.cashPrefix() != null && !r.cashPrefix().isBlank()) s.setCashPrefix(r.cashPrefix().trim());

        // Saiz tidak munasabah menghasilkan nombor tidak boleh dibaca atau
        // String.format yang gagal — sama seperti DocumentNumberService.
        if (r.pvNoSize() >= 1 && r.pvNoSize() <= 18) s.setPvNoSize(r.pvNoSize());
        if (r.cashNoSize() >= 1 && r.cashNoSize() <= 18) s.setCashNoSize(r.cashNoSize());
        if (r.pvNoStart() >= 0) s.setPvNoStart(r.pvNoStart());
        if (r.cashNoStart() >= 0) s.setCashNoStart(r.cashNoStart());

        s.setBankGlAccountId(r.bankGlAccountId());
        return ResponseEntity.ok(SettingDto.from(s));
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
