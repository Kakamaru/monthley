package com.monthley.expenses.internal;

import com.monthley.shared.Access;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Hak modul untuk SP semasa — dibaca oleh UI.
 *
 * UI perlukan ini untuk 'benarkan masuk, sekat transaksi' (ADR 0016):
 * menu dan skrin kekal boleh dibuka, tetapi butang tulis dikunci dan
 * sebabnya dinyatakan. Tanpa endpoint ini, UI tiada cara mengetahui hak
 * dan setiap percubaan tulis berakhir dengan 403 yang mengejutkan.
 *
 * Ia BUKAN penguatkuasaan. ModuleGuard di backend yang menguatkuasakan;
 * ini hanya memberitahu UI apa yang perlu dipaparkan.
 */
@RestController
@RequestMapping("/api/v1/modules")
class ExpModuleController {

    private final ModuleGuard modules;

    @PersistenceContext
    private EntityManager em;

    ExpModuleController(ModuleGuard modules) {
        this.modules = modules;
    }

    record ModuleStatus(String code, String name, boolean active,
                        String description, String videoUrl) {}

    /**
     * Semua modul dalam katalog, dengan status langganan SP semasa.
     *
     * Modul yang TIDAK dilanggan turut dipulangkan — itulah yang
     * membolehkan UI memaparkannya sebagai tawaran dan bukan
     * menyembunyikannya.
     */
    @GetMapping
    @SuppressWarnings("unchecked")
    List<ModuleStatus> list() {
        Access.requireAnyRole("melihat modul", "SP_ADMIN", "CLERK", "VIEWER");
        String sp = sp();

        List<Object[]> rows = em.createNativeQuery("""
                SELECT m.code, m.name, m.description, m.video_url,
                       EXISTS (SELECT 1 FROM sp_module s
                               WHERE s.sp_code = :sp AND s.module_code = m.code
                                 AND s.status = 'ACTIVE'
                                 AND s.start_date <= CURDATE()
                                 AND (s.end_date IS NULL OR s.end_date >= CURDATE())) AS aktif
                FROM   ref_module m
                WHERE  m.status = 'ACTIVE'
                ORDER  BY m.sort_order
                """).setParameter("sp", sp).getResultList();

        List<ModuleStatus> out = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            out.add(new ModuleStatus((String) r[0], (String) r[1],
                    bool(r[4]), (String) r[2], (String) r[3]));
        }
        return out;
    }

    /** Semakan pantas untuk satu modul. */
    @GetMapping("/{code}")
    Map<String, Boolean> has(@org.springframework.web.bind.annotation.PathVariable String code) {
        Access.requireAnyRole("melihat modul", "SP_ADMIN", "CLERK", "VIEWER");
        return Map.of("active", modules.has(code));
    }

    private static boolean bool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
