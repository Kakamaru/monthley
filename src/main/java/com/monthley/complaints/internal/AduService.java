package com.monthley.complaints.internal;

import com.monthley.document.api.DocumentNumberPort;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Aduan — cipta, balas, kemas kini status.
 *
 * Aduan boleh dicipta oleh DUA pihak:
 *
 *   Pelanggan — melalui portal sendiri. Pengadu ialah pengguna yang log
 *               masuk, jadi nama dan telefon datang daripada profilnya.
 *
 *   Kerani SP — merekod aduan daripada panggilan telefon. Pengadu bukan
 *               pengguna sistem, jadi nama dan telefon ditaip.
 *
 * Kedua-duanya menghasilkan aduan yang sama; yang berbeza hanya dari mana
 * butiran pengadu datang. Memisahkannya kepada dua jadual bermakna setiap
 * laporan perlu menggabungkan semula.
 */
@Service
class AduService {

    private final AduComplaintRepository complaints;
    private final AduReplyRepository replies;
    private final AduCategoryRepository categories;
    private final AduSettingRepository settings;
    private final DocumentNumberPort numbers;
    private final ModuleGuard modules;

    @PersistenceContext
    private EntityManager em;

    AduService(AduComplaintRepository complaints, AduReplyRepository replies,
               AduCategoryRepository categories, AduSettingRepository settings,
               DocumentNumberPort numbers, ModuleGuard modules) {
        this.complaints = complaints;
        this.replies = replies;
        this.categories = categories;
        this.settings = settings;
        this.numbers = numbers;
        this.modules = modules;
    }

    record NewComplaint(Long accountId, Long categoryId, String subject, String detail,
                        String priority, String reporterName, String reporterPhone) {}

    /**
     * Cipta aduan.
     *
     * @param byUser   pengguna yang log masuk; NULL bila kerani merekod
     *                 aduan bagi pihak orang lain
     * @param fromSp   true bila kerani merekod — menentukan sama ada
     *                 butiran pengadu ditaip atau diambil dari profil
     */
    @Transactional
    Long create(NewComplaint req, Long byUser, boolean fromSp) {
        modules.require(ModuleGuard.ADUAN, "merekod aduan");
        String sp = sp();

        if (req.accountId() == null) {
            throw new IllegalStateException("Akaun wajib dipilih.");
        }
        // Akaun mesti milik SP ini. Tanpa semakan, pengadu boleh menghantar
        // id akaun sewenang-wenang dan aduan muncul pada SP yang salah.
        Number milik = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM account WHERE id = :a AND sp_code = :sp")
                .setParameter("a", req.accountId()).setParameter("sp", sp)
                .getSingleResult();
        if (milik.intValue() == 0) {
            throw new IllegalStateException("Akaun tidak wujud untuk organisasi ini.");
        }

        String subject = req.subject() == null ? "" : req.subject().trim();
        if (subject.isBlank()) {
            throw new IllegalStateException("Tajuk aduan wajib diisi.");
        }
        if (req.categoryId() != null) {
            categories.findByIdAndSpCode(req.categoryId(), sp).orElseThrow(
                    () -> new IllegalStateException("Kategori tidak wujud."));
        }

        AduSetting setting = settings.findById(sp).orElseGet(() -> settings.save(new AduSetting(sp)));
        String no = numbers.next(sp, "ADU_COMPLAINT", setting.getPrefix(),
                setting.getNoSize(), setting.getNoStart());

        AduComplaint c = new AduComplaint(sp, no, req.accountId(), subject);
        c.setCategoryId(req.categoryId());
        c.setDetail(req.detail());
        c.setPriority(priority(req.priority()));
        c.setReporterName(req.reporterName());
        c.setReporterPhone(req.reporterPhone());
        // Pengadu direkod hanya apabila dia pengguna sistem. Kerani yang
        // merekod bagi pihak orang lain bukan pengadu.
        c.setReportedBy(fromSp ? null : byUser);

        return complaints.save(c).getId();
    }

    record ReplyRequest(String message, String status, Long assignedTo,
                        String internalNote, boolean internal) {}

    /**
     * Balas aduan dan kemas kini status dalam satu operasi.
     *
     * Digabungkan kerana itulah cara ia berlaku: SP membalas DAN menukar
     * status pada masa yang sama. Dua endpoint bermakna dua panggilan
     * yang boleh gagal separuh jalan, dan aduan yang dibalas tetapi
     * statusnya tidak berubah.
     */
    @Transactional
    void reply(Long complaintId, ReplyRequest req, Long byUser, boolean fromSp) {
        modules.require(ModuleGuard.ADUAN, "membalas aduan");
        String sp = sp();

        AduComplaint c = complaints.findByIdAndSpCode(complaintId, sp).orElseThrow(
                () -> new IllegalStateException("Aduan tidak wujud: " + complaintId));

        String mesej = req.message() == null ? "" : req.message().trim();
        if (!mesej.isBlank()) {
            replies.save(new AduReply(c.getId(), mesej, byUser, fromSp, req.internal()));
        }

        // Hanya SP boleh menukar status, menugaskan, dan menulis nota
        // dalaman. Pengadu yang membalas tidak sepatutnya menutup aduannya
        // sendiri — atau membukanya semula tanpa had.
        if (fromSp) {
            if (req.status() != null && !req.status().isBlank()) {
                c.setStatus(AduComplaint.Status.valueOf(req.status().trim().toUpperCase()));
            }
            if (req.assignedTo() != null) c.setAssignedTo(req.assignedTo());
            if (req.internalNote() != null) c.setInternalNote(req.internalNote());
        } else if (c.getStatus() == AduComplaint.Status.RESOLVED) {
            // Pengadu membalas aduan yang sudah selesai — ia dibuka semula.
            // Dashboard mengira ini; ia menunjukkan penyelesaian yang tidak
            // benar-benar menyelesaikan masalah.
            c.setStatus(AduComplaint.Status.REOPENED);
        }

        if (mesej.isBlank() && !fromSp) {
            throw new IllegalStateException("Mesej balasan wajib diisi.");
        }
    }

    private static AduComplaint.Priority priority(String v) {
        if (v == null || v.isBlank()) return AduComplaint.Priority.MEDIUM;
        try {
            return AduComplaint.Priority.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AduComplaint.Priority.MEDIUM;
        }
    }

    private String sp() {
        String s = TenantContext.get();
        if (s == null || s.isBlank()) {
            throw new IllegalStateException("Tiada konteks SP.");
        }
        return s;
    }
}
