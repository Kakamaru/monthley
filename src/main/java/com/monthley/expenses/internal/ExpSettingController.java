package com.monthley.expenses.internal;

import com.monthley.shared.Access;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    private final ExpPaymentMethodRepository methods;
    private final ModuleGuard modules;

    @PersistenceContext
    private EntityManager em;

    ExpSettingController(ExpSettingRepository settings,
                         ExpPaymentMethodRepository methods,
                         ModuleGuard modules) {
        this.settings = settings;
        this.methods = methods;
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

    record GlOption(Long id, String code, String name) {}

    /**
     * Akaun yang sah sebagai sumber duit keluar — jenis ASSET sahaja.
     *
     * Bayaran mengkredit akaun ini. Memilih akaun belanja atau hasil di
     * sini bermakna setiap PV memposkan kredit ke tempat yang salah, dan
     * jurnal tetap seimbang — kesilapan yang hanya kelihatan bila
     * Imbangan Duga disemak.
     */
    @GetMapping("/bank-accounts")
    @SuppressWarnings("unchecked")
    List<GlOption> bankAccounts() {
        Access.requireAnyRole("melihat akaun bank", "SP_ADMIN", "CLERK");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, code, name FROM chart_of_accounts
                WHERE  sp_code = :sp AND account_type = 'ASSET' AND status = 'ACTIVE'
                  AND  is_control = 0
                ORDER  BY code
                """).setParameter("sp", sp()).getResultList();

        List<GlOption> out = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            out.add(new GlOption(((Number) r[0]).longValue(), (String) r[1], (String) r[2]));
        }
        return out;
    }

    // ---------- Kaedah bayaran ----------

    record MethodDto(Long id, String name, int sortOrder, boolean active) {
        static MethodDto from(ExpPaymentMethod m) {
            return new MethodDto(m.getId(), m.getName(), m.getSortOrder(),
                    m.getStatus() == ExpPaymentMethod.Status.ACTIVE);
        }
    }

    record SaveMethodRequest(String name, Integer sortOrder, Boolean active) {}

    @GetMapping("/methods")
    List<MethodDto> methods() {
        Access.requireAnyRole("melihat kaedah bayaran", "SP_ADMIN", "CLERK");
        return methods.findBySpCodeOrderBySortOrderAscNameAsc(sp())
                .stream().map(MethodDto::from).toList();
    }

    @PostMapping("/methods")
    @Transactional
    ResponseEntity<?> createMethod(@RequestBody SaveMethodRequest r) {
        Access.requireRole("SP_ADMIN", "menambah kaedah bayaran");
        modules.require(ModuleGuard.PERBELANJAAN, "menambah kaedah bayaran");

        String nama = r.name() == null ? "" : r.name().trim();
        if (nama.isBlank()) {
            throw new IllegalStateException("Nama kaedah wajib diisi.");
        }
        ExpPaymentMethod m = new ExpPaymentMethod(sp(), nama);
        if (r.sortOrder() != null) m.setSortOrder(r.sortOrder());
        return ResponseEntity.ok(MethodDto.from(methods.save(m)));
    }

    @PutMapping("/methods/{id}")
    @Transactional
    ResponseEntity<?> updateMethod(@PathVariable Long id, @RequestBody SaveMethodRequest r) {
        Access.requireRole("SP_ADMIN", "mengubah kaedah bayaran");
        modules.require(ModuleGuard.PERBELANJAAN, "mengubah kaedah bayaran");

        ExpPaymentMethod m = methods.findByIdAndSpCode(id, sp()).orElseThrow(
                () -> new IllegalStateException("Kaedah tidak wujud: " + id));

        String nama = r.name() == null ? "" : r.name().trim();
        if (!nama.isBlank()) m.setName(nama);
        if (r.sortOrder() != null) m.setSortOrder(r.sortOrder());
        if (r.active() != null) {
            m.setStatus(r.active() ? ExpPaymentMethod.Status.ACTIVE
                                   : ExpPaymentMethod.Status.INACTIVE);
        }
        return ResponseEntity.ok(MethodDto.from(m));
    }

    /**
     * Nyahaktif, bukan padam.
     *
     * Transaksi menyimpan NAMA kaedah, bukan id — jadi memadamnya tidak
     * akan memecahkan FK. Tetapi kaedah yang hilang dari senarai sedangkan
     * baucar lama masih memaparkannya menjadikan laporan sukar difahami.
     */
    @DeleteMapping("/methods/{id}")
    @Transactional
    ResponseEntity<?> deactivateMethod(@PathVariable Long id) {
        Access.requireRole("SP_ADMIN", "menyahaktifkan kaedah bayaran");
        modules.require(ModuleGuard.PERBELANJAAN, "menyahaktifkan kaedah bayaran");

        ExpPaymentMethod m = methods.findByIdAndSpCode(id, sp()).orElseThrow(
                () -> new IllegalStateException("Kaedah tidak wujud: " + id));
        m.setStatus(ExpPaymentMethod.Status.INACTIVE);
        return ResponseEntity.ok(Map.of("message", "Kaedah " + m.getName() + " dinyahaktifkan."));
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
