package com.monthley.expenses.internal;

import com.monthley.shared.Access;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Kategori perbelanjaan — pokok dua aras.
 *
 *   GET    /api/v1/expenses/categories
 *   POST   /api/v1/expenses/categories
 *   PUT    /api/v1/expenses/categories/{id}
 *   DELETE /api/v1/expenses/categories/{id}   (nyahaktif, bukan padam)
 *
 * BACA dibenarkan tanpa hak modul — skrin boleh dibuka dan menunjukkan apa
 * yang ditawarkan. TULIS dilindungi ModuleGuard (ADR 0016).
 */
@RestController
@RequestMapping("/api/v1/expenses/categories")
class ExpCategoryController {

    private final ExpCategoryRepository categories;
    private final ModuleGuard modules;

    @PersistenceContext
    private EntityManager em;

    ExpCategoryController(ExpCategoryRepository categories, ModuleGuard modules) {
        this.categories = categories;
        this.modules = modules;
    }

    record CategoryDto(Long id, String name, Long parentId, Long glAccountId,
                       int sortOrder, boolean active) {
        static CategoryDto from(ExpCategory c) {
            return new CategoryDto(c.getId(), c.getName(), c.getParentId(),
                    c.getGlAccountId(), c.getSortOrder(),
                    c.getStatus() == ExpCategory.Status.ACTIVE);
        }
    }

    record SaveRequest(@NotBlank String name, Long parentId, Long glAccountId,
                       Integer sortOrder, Boolean active) {}

    /**
     * Senarai kategori dalam susunan POKOK: setiap induk diikuti anaknya.
     *
     * Susunan rata (sortOrder, name) menghasilkan 'Elektrik, Gaji & Upah,
     * Utiliti...' — anak bercampur dengan induk dan skrin tidak boleh
     * memaparkan hierarki tanpa menyusun semula sendiri.
     */
    @GetMapping
    List<CategoryDto> list() {
        Access.requireAnyRole("melihat kategori perbelanjaan", "SP_ADMIN", "CLERK");

        List<ExpCategory> semua = categories.findBySpCodeOrderBySortOrderAscNameAsc(sp());

        List<CategoryDto> out = new java.util.ArrayList<>();
        for (ExpCategory induk : semua) {
            if (induk.getParentId() != null) continue;
            out.add(CategoryDto.from(induk));
            for (ExpCategory anak : semua) {
                if (induk.getId().equals(anak.getParentId())) {
                    out.add(CategoryDto.from(anak));
                }
            }
        }
        // Anak yatim (induk dinyahaktif atau dipadam) tetap disenaraikan —
        // menyembunyikannya bermakna kategori yang masih dirujuk oleh invois
        // hilang dari skrin tanpa penjelasan.
        for (ExpCategory c : semua) {
            if (c.getParentId() != null
                    && semua.stream().noneMatch(p -> p.getId().equals(c.getParentId()))) {
                out.add(CategoryDto.from(c));
            }
        }
        return out;
    }

    record GlOption(Long id, String code, String name) {}

    /**
     * Akaun GL yang sah untuk kategori perbelanjaan — jenis EXPENSE sahaja.
     *
     * Memaparkan carta akaun penuh bermakna seseorang boleh memetakan
     * kategori kepada Bank atau Accounts Receivable, dan posting akan
     * kelihatan seimbang sambil merosakkan penyata. Penapisan di sini
     * menjadikan kesilapan itu mustahil dan bukan sekadar tidak digalakkan.
     */
    @GetMapping("/gl-accounts")
    @SuppressWarnings("unchecked")
    List<GlOption> glAccounts() {
        Access.requireAnyRole("melihat carta akaun", "SP_ADMIN", "CLERK");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, code, name FROM chart_of_accounts
                WHERE  sp_code = :sp AND account_type = 'EXPENSE' AND status = 'ACTIVE'
                ORDER  BY code
                """).setParameter("sp", sp()).getResultList();

        List<GlOption> out = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            out.add(new GlOption(((Number) r[0]).longValue(), (String) r[1], (String) r[2]));
        }
        return out;
    }

    @PostMapping
    @Transactional
    ResponseEntity<?> create(@Valid @RequestBody SaveRequest r) {
        Access.requireRole("SP_ADMIN", "menambah kategori perbelanjaan");
        modules.require(ModuleGuard.PERBELANJAAN, "menambah kategori");

        ExpCategory c = new ExpCategory(sp(), r.name().trim(), r.parentId());
        apply(c, r);
        return ResponseEntity.ok(CategoryDto.from(categories.save(c)));
    }

    @PutMapping("/{id}")
    @Transactional
    ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody SaveRequest r) {
        Access.requireRole("SP_ADMIN", "mengubah kategori perbelanjaan");
        modules.require(ModuleGuard.PERBELANJAAN, "mengubah kategori");

        ExpCategory c = categories.findByIdAndSpCode(id, sp()).orElseThrow(
                () -> new IllegalStateException("Kategori tidak wujud: " + id));
        c.setName(r.name().trim());
        c.setParentId(r.parentId());
        apply(c, r);
        return ResponseEntity.ok(CategoryDto.from(c));
    }

    /**
     * Nyahaktif, BUKAN padam.
     *
     * Kategori dirujuk oleh invois dan bayaran yang sudah dipos ke ledger.
     * Memadamnya meninggalkan baris yang tidak boleh menerangkan dirinya —
     * dan FK akan menolaknya, jadi 'padam' hanya berfungsi untuk kategori
     * yang belum pernah diguna. Nyahaktif berkelakuan sama bagi pengguna
     * tanpa lubang itu.
     */
    @DeleteMapping("/{id}")
    @Transactional
    ResponseEntity<?> deactivate(@PathVariable Long id) {
        Access.requireRole("SP_ADMIN", "menyahaktifkan kategori perbelanjaan");
        modules.require(ModuleGuard.PERBELANJAAN, "menyahaktifkan kategori");

        ExpCategory c = categories.findByIdAndSpCode(id, sp()).orElseThrow(
                () -> new IllegalStateException("Kategori tidak wujud: " + id));
        c.setStatus(ExpCategory.Status.INACTIVE);
        return ResponseEntity.ok(Map.of("message", "Kategori " + c.getName() + " dinyahaktifkan."));
    }

    private void apply(ExpCategory c, SaveRequest r) {
        // GL hanya pada kategori INDUK; anak mewarisi. Menetapkan GL pada
        // anak mencipta dua sumber kebenaran untuk baris yang sama.
        c.setGlAccountId(r.parentId() == null ? r.glAccountId() : null);
        if (r.sortOrder() != null) c.setSortOrder(r.sortOrder());
        if (r.active() != null) {
            c.setStatus(r.active() ? ExpCategory.Status.ACTIVE : ExpCategory.Status.INACTIVE);
        }
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
