package com.monthley.expenses.internal;

import com.monthley.shared.Access;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Pembekal.
 *
 *   GET    /api/v1/expenses/suppliers
 *   POST   /api/v1/expenses/suppliers
 *   PUT    /api/v1/expenses/suppliers/{id}
 *   DELETE /api/v1/expenses/suppliers/{id}   (nyahaktif)
 */
@RestController
@RequestMapping("/api/v1/expenses/suppliers")
class ExpSupplierController {

    private final ExpSupplierRepository suppliers;
    private final ModuleGuard modules;

    ExpSupplierController(ExpSupplierRepository suppliers, ModuleGuard modules) {
        this.suppliers = suppliers;
        this.modules = modules;
    }

    record SupplierDto(Long id, String name, String regNo, String tin, String address,
                       String phone, String email, String bankName, String bankAccNo,
                       boolean active) {
        static SupplierDto from(ExpSupplier s) {
            return new SupplierDto(s.getId(), s.getName(), s.getRegNo(), s.getTin(),
                    s.getAddress(), s.getPhone(), s.getEmail(), s.getBankName(),
                    s.getBankAccNo(), s.getStatus() == ExpSupplier.Status.ACTIVE);
        }
    }

    record SaveRequest(@NotBlank String name, String regNo, String tin, String address,
                       String phone, String email, String bankName, String bankAccNo,
                       Boolean active) {}

    @GetMapping
    List<SupplierDto> list() {
        Access.requireAnyRole("melihat senarai pembekal", "SP_ADMIN", "CLERK");
        return suppliers.findBySpCodeOrderByNameAsc(sp())
                .stream().map(SupplierDto::from).toList();
    }

    @PostMapping
    @Transactional
    ResponseEntity<?> create(@Valid @RequestBody SaveRequest r) {
        Access.requireRole("SP_ADMIN", "menambah pembekal");
        modules.require(ModuleGuard.PERBELANJAAN, "menambah pembekal");

        ExpSupplier s = new ExpSupplier(sp(), r.name().trim());
        apply(s, r);
        return ResponseEntity.ok(SupplierDto.from(suppliers.save(s)));
    }

    @PutMapping("/{id}")
    @Transactional
    ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody SaveRequest r) {
        Access.requireRole("SP_ADMIN", "mengubah pembekal");
        modules.require(ModuleGuard.PERBELANJAAN, "mengubah pembekal");

        ExpSupplier s = suppliers.findByIdAndSpCode(id, sp()).orElseThrow(
                () -> new IllegalStateException("Pembekal tidak wujud: " + id));
        s.setName(r.name().trim());
        apply(s, r);
        return ResponseEntity.ok(SupplierDto.from(s));
    }

    /** Nyahaktif, bukan padam — pembekal dirujuk oleh invois sedia ada. */
    @DeleteMapping("/{id}")
    @Transactional
    ResponseEntity<?> deactivate(@PathVariable Long id) {
        Access.requireRole("SP_ADMIN", "menyahaktifkan pembekal");
        modules.require(ModuleGuard.PERBELANJAAN, "menyahaktifkan pembekal");

        ExpSupplier s = suppliers.findByIdAndSpCode(id, sp()).orElseThrow(
                () -> new IllegalStateException("Pembekal tidak wujud: " + id));
        s.setStatus(ExpSupplier.Status.INACTIVE);
        return ResponseEntity.ok(Map.of("message", "Pembekal " + s.getName() + " dinyahaktifkan."));
    }

    private void apply(ExpSupplier s, SaveRequest r) {
        s.setRegNo(r.regNo());
        s.setTin(r.tin());
        s.setAddress(r.address());
        s.setPhone(r.phone());
        s.setEmail(r.email());
        s.setBankName(r.bankName());
        s.setBankAccNo(r.bankAccNo());
        if (r.active() != null) {
            s.setStatus(r.active() ? ExpSupplier.Status.ACTIVE : ExpSupplier.Status.INACTIVE);
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
