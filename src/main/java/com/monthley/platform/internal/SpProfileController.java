package com.monthley.platform.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Profil SP — superadmin sahaja.
 *
 * DIPISAHKAN daripada pelan dan modul dengan sengaja (ADR 0016):
 *
 *   PROFIL       — nama, alamat, telefon, bank. Ditukar TERUS; ia
 *                  pembetulan maklumat, bukan komitmen kewangan.
 *
 *   PELAN/MODUL  — melalui sp_change_request dengan kelulusan.
 *
 * Satu skrin untuk kedua-duanya bermakna DUA laluan menukar pelan: satu
 * dengan kelulusan dan satu tanpa. Laluan kedua itu memintas rekod
 * permohonan, dan bil berubah tanpa apa-apa jejak siapa membenarkannya.
 *
 * Laluan /api/v1/platform/** sudah disekat kepada SUPERADMIN dalam
 * SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/platform/service-providers")
class SpProfileController {

    @PersistenceContext
    private EntityManager em;

    record SpProfile(
            String spCode, String name, String handle,
            String businessType, String businessDesc, String registrationNo,
            LocalDate orgRegisteredDate, String website,
            String addrLine1, String addrLine2, String addrLine3,
            String city, String postcode, String state, String country,
            String phone, String officeNo, String mobileNo,
            String contactEmail, String helpdeskEmail, String helpdeskPhone,
            String bankCode, String bankAccountNo, String bankAccountName,
            Integer estInvoicesMonth, BigDecimal minPymtAmount,
            boolean allowSelective, BigDecimal minDenom,
            String status,
            // Baca sahaja — ditukar melalui sp_change_request.
            Long planProductId, String planName, BigDecimal planPrice,
            Integer accountLimit, long accountCount) {}

    record SaveProfile(
            String name, String handle,
            String businessType, String businessDesc, String registrationNo,
            LocalDate orgRegisteredDate, String website,
            String addrLine1, String addrLine2, String addrLine3,
            String city, String postcode, String state, String country,
            String phone, String officeNo, String mobileNo,
            String contactEmail, String helpdeskEmail, String helpdeskPhone,
            String bankCode, String bankAccountNo, String bankAccountName,
            Integer estInvoicesMonth, BigDecimal minPymtAmount,
            Boolean allowSelective, BigDecimal minDenom,
            String status) {}

    @GetMapping("/{spCode}")
    SpProfile get(@PathVariable String spCode) {
        List<?> rows = em.createNativeQuery("""
                SELECT sp.sp_code, sp.name, sp.handle,
                       sp.business_type, sp.business_desc, sp.registration_no,
                       sp.org_registered_date, sp.website,
                       sp.addr_line1, sp.addr_line2, sp.addr_line3,
                       sp.city, sp.postcode, sp.state, sp.country,
                       sp.phone, sp.office_no, sp.mobile_no,
                       sp.contact_email, sp.helpdesk_email, sp.helpdesk_phone,
                       sp.bank_code, sp.bank_account_no, sp.bank_account_name,
                       sp.est_invoices_month, sp.min_pymt_amount,
                       sp.allow_selective, sp.min_denom, sp.status,
                       sp.plan_product_id, p.name, p.unit_rate, p.account_limit,
                       (SELECT COUNT(*) FROM account a
                        WHERE a.sp_code = sp.sp_code AND a.status = 'ACTIVE')
                FROM   service_provider sp
                LEFT   JOIN product p ON p.id = sp.plan_product_id
                WHERE  sp.sp_code = :sp
                """).setParameter("sp", spCode).getResultList();

        if (rows.isEmpty()) {
            throw new IllegalStateException("SP tidak wujud: " + spCode);
        }
        Object[] r = (Object[]) rows.get(0);

        return new SpProfile(
                (String) r[0], (String) r[1], (String) r[2],
                (String) r[3], (String) r[4], (String) r[5],
                toDate(r[6]), (String) r[7],
                (String) r[8], (String) r[9], (String) r[10],
                (String) r[11], (String) r[12], (String) r[13], (String) r[14],
                (String) r[15], (String) r[16], (String) r[17],
                (String) r[18], (String) r[19], (String) r[20],
                (String) r[21], (String) r[22], (String) r[23],
                r[24] == null ? null : ((Number) r[24]).intValue(),
                (BigDecimal) r[25],
                bool(r[26]), (BigDecimal) r[27], (String) r[28],
                r[29] == null ? null : ((Number) r[29]).longValue(),
                (String) r[30], (BigDecimal) r[31],
                r[32] == null ? null : ((Number) r[32]).intValue(),
                ((Number) r[33]).longValue());
    }

    /**
     * Kemas kini profil.
     *
     * plan_product_id TIDAK diterima di sini walaupun ia medan pada jadual
     * yang sama — menukarnya mengubah bil, dan itu melalui kelulusan.
     *
     * Status DIBENARKAN: menggantung SP yang tidak membayar ialah tindakan
     * platform, bukan permintaan SP. Ia tidak masuk akal untuk melaluinya
     * sebagai permohonan yang SP sendiri hantar.
     */
    @PutMapping("/{spCode}")
    @Transactional
    ResponseEntity<?> update(@PathVariable String spCode, @RequestBody SaveProfile r) {
        String nama = r.name() == null ? "" : r.name().trim();
        if (nama.isBlank()) {
            throw new IllegalStateException("Nama SP wajib diisi.");
        }

        Number wujud = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM service_provider WHERE sp_code = :sp")
                .setParameter("sp", spCode).getSingleResult();
        if (wujud.intValue() == 0) {
            throw new IllegalStateException("SP tidak wujud: " + spCode);
        }

        String status = (r.status() == null || r.status().isBlank())
                ? null : r.status().trim().toUpperCase();
        if (status != null && !List.of("PENDING", "ACTIVE", "SUSPENDED", "CLOSED").contains(status)) {
            throw new IllegalStateException("Status tidak sah: " + status);
        }

        em.createNativeQuery("""
                UPDATE service_provider SET
                    name = :name, handle = :handle,
                    business_type = :bizType, business_desc = :bizDesc,
                    registration_no = :regNo, org_registered_date = :orgDate,
                    website = :website,
                    addr_line1 = :a1, addr_line2 = :a2, addr_line3 = :a3,
                    city = :city, postcode = :postcode, state = :state, country = :country,
                    phone = :phone, office_no = :officeNo, mobile_no = :mobileNo,
                    contact_email = :email, helpdesk_email = :hdEmail,
                    helpdesk_phone = :hdPhone,
                    bank_code = :bankCode, bank_account_no = :bankNo,
                    bank_account_name = :bankName,
                    est_invoices_month = :estInv, min_pymt_amount = :minPay,
                    allow_selective = :allowSel, min_denom = :minDenom,
                    status = COALESCE(:status, status),
                    updated_at = NOW(), updated_by = 'platform'
                WHERE sp_code = :sp
                """)
                .setParameter("name", nama)
                .setParameter("handle", kosongJadiNull(r.handle()))
                .setParameter("bizType", kosongJadiNull(r.businessType()))
                .setParameter("bizDesc", kosongJadiNull(r.businessDesc()))
                .setParameter("regNo", kosongJadiNull(r.registrationNo()))
                .setParameter("orgDate", r.orgRegisteredDate())
                .setParameter("website", kosongJadiNull(r.website()))
                .setParameter("a1", kosongJadiNull(r.addrLine1()))
                .setParameter("a2", kosongJadiNull(r.addrLine2()))
                .setParameter("a3", kosongJadiNull(r.addrLine3()))
                .setParameter("city", kosongJadiNull(r.city()))
                .setParameter("postcode", kosongJadiNull(r.postcode()))
                .setParameter("state", kosongJadiNull(r.state()))
                .setParameter("country", kosongJadiNull(r.country()))
                .setParameter("phone", kosongJadiNull(r.phone()))
                .setParameter("officeNo", kosongJadiNull(r.officeNo()))
                .setParameter("mobileNo", kosongJadiNull(r.mobileNo()))
                .setParameter("email", kosongJadiNull(r.contactEmail()))
                .setParameter("hdEmail", kosongJadiNull(r.helpdeskEmail()))
                .setParameter("hdPhone", kosongJadiNull(r.helpdeskPhone()))
                .setParameter("bankCode", kosongJadiNull(r.bankCode()))
                .setParameter("bankNo", kosongJadiNull(r.bankAccountNo()))
                .setParameter("bankName", kosongJadiNull(r.bankAccountName()))
                .setParameter("estInv", r.estInvoicesMonth())
                .setParameter("minPay", r.minPymtAmount())
                .setParameter("allowSel", r.allowSelective() != null && r.allowSelective())
                .setParameter("minDenom", r.minDenom())
                .setParameter("status", status)
                .setParameter("sp", spCode)
                .executeUpdate();

        return ResponseEntity.ok(Map.of("message", "Profil " + spCode + " dikemas kini."));
    }

    // ---------- helper ----------

    /**
     * Rentetan kosong disimpan sebagai NULL.
     *
     * Borang menghantar "" untuk medan yang dikosongkan, dan "" berbeza
     * daripada NULL dalam query — `WHERE phone IS NULL` terlepas baris
     * dengan rentetan kosong.
     */
    private static String kosongJadiNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean bool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    private static LocalDate toDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }
}
