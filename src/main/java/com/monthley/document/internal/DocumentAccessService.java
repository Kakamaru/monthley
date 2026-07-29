package com.monthley.document.internal;

import com.monthley.document.api.DocumentAccessPort;
import com.monthley.document.api.DocumentType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Token capaian dokumen (V42).
 *
 * Token ialah 32 bait rawak selamat kripto, dikodkan base64url — 256 bit
 * entropi. Meneka satu tidak boleh dilakukan; UUID legacy memberi kira-kira
 * 122 bit dan itu pun mencukupi, tetapi UUID juga membocorkan masa
 * penciptaan pada sesetengah versi.
 */
@Service
class DocumentAccessService implements DocumentAccessPort {

    private static final SecureRandom RANDOM = new SecureRandom();

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public String tokenFor(String spCode, long documentId, DocumentType type) {
        var sedia = em.createNativeQuery(
                "SELECT token FROM document_access_token WHERE document_id = :id")
                .setParameter("id", documentId)
                .getResultList();
        if (!sedia.isEmpty()) {
            return sedia.get(0).toString();
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        em.createNativeQuery("""
                INSERT INTO document_access_token
                  (sp_code, token, document_id, doc_type)
                VALUES (:sp, :tok, :id, :type)
                """)
                .setParameter("sp", spCode)
                .setParameter("tok", token)
                .setParameter("id", documentId)
                .setParameter("type", type.name())
                .executeUpdate();
        return token;
    }

    @Override
    @Transactional
    public Optional<ResolvedDocument> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        var rows = em.createNativeQuery("""
                SELECT sp_code, document_id, doc_type
                FROM   document_access_token
                WHERE  token = :tok AND revoked_at IS NULL
                """)
                .setParameter("tok", token)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        // Rekod paparan. Berguna untuk SP: legacy tidak boleh menjawab
        // 'adakah pelanggan pernah membuka resit ini'.
        em.createNativeQuery("""
                UPDATE document_access_token
                SET    view_count    = view_count + 1,
                       first_seen_at = COALESCE(first_seen_at, NOW()),
                       last_seen_at  = NOW()
                WHERE  token = :tok
                """)
                .setParameter("tok", token)
                .executeUpdate();

        Object[] r = (Object[]) rows.get(0);
        return Optional.of(new ResolvedDocument(
                (String) r[0],
                ((Number) r[1]).longValue(),
                DocumentType.valueOf(r[2].toString())));
    }

    @Override
    @Transactional
    public void revoke(long documentId) {
        em.createNativeQuery("""
                UPDATE document_access_token
                SET    revoked_at = NOW()
                WHERE  document_id = :id AND revoked_at IS NULL
                """)
                .setParameter("id", documentId)
                .executeUpdate();
    }
}
