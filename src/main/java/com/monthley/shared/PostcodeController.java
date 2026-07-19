package com.monthley.shared;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Lookup poskod Malaysia (rujukan table postcode, V23).
 *
 *   GET /api/v1/lookup/postcode/{code}
 *     -> { postcode, state, cities: [...] }
 *
 * Negeri biasanya satu untuk satu poskod (auto-isi terus). Bandar boleh
 * lebih dari satu (cth 50000 = beberapa kawasan KL) — dikembalikan sebagai
 * senarai untuk dicadangkan kepada pengguna.
 *
 * Reference data awam — tiada tapisan SP.
 */
@RestController
@RequestMapping("/api/v1/lookup")
class PostcodeController {

    @PersistenceContext
    private EntityManager em;

    record PostcodeResult(String postcode, String state, List<String> cities) {}

    @GetMapping("/postcode/{code}")
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    ResponseEntity<PostcodeResult> lookup(@PathVariable String code) {
        String pc = code == null ? "" : code.trim();
        if (pc.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Object[]> rows = em.createNativeQuery(
                "SELECT DISTINCT city, state FROM postcode WHERE postcode = :pc ORDER BY city")
                .setParameter("pc", pc)
                .getResultList();

        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<String> cities = new ArrayList<>();
        String state = null;
        for (Object[] r : rows) {
            cities.add((String) r[0]);
            if (state == null) state = (String) r[1];
        }

        return ResponseEntity.ok(new PostcodeResult(pc, state, cities));
    }
}
