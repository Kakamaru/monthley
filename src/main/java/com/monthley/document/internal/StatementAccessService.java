package com.monthley.document.internal;

import com.monthley.document.api.StatementAccessPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Token capaian penyata (V57).
 *
 * Corak sama seperti DocumentAccessService: 32 bait rawak selamat
 * kripto, base64url, 256 bit entropi. UUID legacy memberi kira-kira 122
 * bit — mencukupi, tetapi ia juga membocorkan masa penciptaan pada
 * sesetengah versi.
 */
@Service
class StatementAccessService implements StatementAccessPort {

    private static final SecureRandom RANDOM = new SecureRandom();

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public String tokenFor(String spCode, long accountId, int year) {
        var sedia = em.createNativeQuery(
                "SELECT token FROM statement_access_token "
                + "WHERE account_id = :acc AND stmt_year = :yr")
                .setParameter("acc", accountId)
                .setParameter("yr", year)
                .getResultList();
        if (!sedia.isEmpty()) {
            return sedia.get(0).toString();
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        em.createNativeQuery("""
                INSERT INTO statement_access_token
                  (sp_code, token, account_id, stmt_year)
                VALUES (:sp, :tok, :acc, :yr)
                """)
                .setParameter("sp", spCode)
                .setParameter("tok", token)
                .setParameter("acc", accountId)
                .setParameter("yr", year)
                .executeUpdate();
        return token;
    }

    @Override
    @Transactional
    public Optional<ResolvedStatement> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        var rows = em.createNativeQuery("""
                SELECT sp_code, account_id, stmt_year
                FROM   statement_access_token
                WHERE  token = :tok AND revoked_at IS NULL
                """)
                .setParameter("tok", token)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        // Rekod paparan. SP boleh menjawab "adakah pelanggan pernah
        // membuka penyata ini" — legacy tidak boleh.
        em.createNativeQuery("""
                UPDATE statement_access_token
                SET    view_count    = view_count + 1,
                       first_seen_at = COALESCE(first_seen_at, NOW()),
                       last_seen_at  = NOW()
                WHERE  token = :tok
                """)
                .setParameter("tok", token)
                .executeUpdate();

        Object[] r = (Object[]) rows.get(0);
        return Optional.of(new ResolvedStatement(
                (String) r[0],
                ((Number) r[1]).longValue(),
                ((Number) r[2]).intValue()));
    }

    @Override
    @Transactional
    public void revoke(long accountId, int year) {
        em.createNativeQuery("""
                UPDATE statement_access_token
                SET    revoked_at = NOW()
                WHERE  account_id = :acc AND stmt_year = :yr AND revoked_at IS NULL
                """)
                .setParameter("acc", accountId)
                .setParameter("yr", year)
                .executeUpdate();
    }
}
