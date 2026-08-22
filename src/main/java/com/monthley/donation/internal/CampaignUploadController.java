package com.monthley.donation.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import com.monthley.storage.api.StoragePort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Muat naik poster kempen.
 *
 * Poster dilihat oleh sesiapa yang membuka pautan derma, jadi ia masuk ke
 * baldi AWAM dan dihidangkan terus oleh Nginx.
 */
@RestController
@RequestMapping("/api/v1/donations/upload")
class CampaignUploadController {

    /** 2MB — poster 1080x1350 berkualiti baik muat dengan selesa.
     *
     *  Had wujud kerana poster dimuatkan pada borang derma awam: fail
     *  besar melambatkan halaman untuk setiap penderma yang membukanya. */
    private static final long MAX_BAIT = 2L * 1024 * 1024;

    private final StoragePort storage;

    CampaignUploadController(StoragePort storage) {
        this.storage = storage;
    }

    @PostMapping("/poster")
    ResponseEntity<?> muatNaik(@RequestParam("file") MultipartFile file) throws IOException {
        Access.requireRole("SP_ADMIN", "memuat naik poster kempen");

        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Tiada konteks SP.");
        }

        if (file.isEmpty()) {
            throw new IllegalStateException("Tiada fail dipilih.");
        }
        if (file.getSize() > MAX_BAIT) {
            throw new IllegalStateException("Saiz fail melebihi 2MB.");
        }

        // Jenis disemak daripada BAIT PERTAMA, bukan daripada nama fail
        // atau content-type yang dihantar pelayar. Kedua-duanya datang
        // daripada klien dan boleh menyatakan apa sahaja; '.jpg' boleh
        // mengandungi HTML yang dilaksanakan apabila dibuka.
        String jenis = kesanJenis(file.getBytes());
        if (jenis == null) {
            throw new IllegalStateException(
                    "Hanya fail JPEG atau PNG dibenarkan.");
        }

        // Nama dijana, bukan nama asal: nama asal boleh mengandungi
        // aksara yang memecahkan laluan, dan dua SP yang memuat naik
        // 'poster.jpg' akan bertindih.
        String sambungan = jenis.equals("image/png") ? "png" : "jpg";
        String key = "campaigns/" + sp + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + sambungan;

        try (var in = file.getInputStream()) {
            storage.put(StoragePort.PUBLIC, key, in, file.getSize(), jenis);
        }

        return ResponseEntity.ok(Map.of(
                "key", key,
                "url", storage.publicUrl(key)));
    }

    /**
     * Jenis imej daripada nombor ajaib.
     *
     * JPEG bermula dengan FF D8 FF; PNG dengan 89 50 4E 47.
     *
     * @return content-type, atau null jika bukan imej yang dibenarkan
     */
    private static String kesanJenis(byte[] b) {
        if (b.length < 4) return null;

        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return null;
    }
}
