package com.monthley.identity.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Cipta superadmin lalai jika belum wujud.
 *
 * Kata laluan datang daripada MONTHLEY_SUPERADMIN_PASSWORD. Lalai
 * "superadmin" dibenarkan HANYA dalam profil dev — pemasangan yang boleh
 * dicapai dari internet dengan kata laluan yang tertulis dalam kod sumber
 * awam ialah pintu terbuka, dan "superadmin" ialah tekaan pertama sesiapa.
 *
 * Dalam profil bukan-dev tanpa pembolehubah itu, aplikasi ENGGAN BOOT.
 * Gagal semasa boot kelihatan serta-merta; superadmin lemah yang dicipta
 * senyap hanya disedari selepas seseorang masuk.
 */
@Configuration
class SuperadminSeeder {

    @Bean
    ApplicationRunner seedSuperadmin(PlatformAdminRepository admins,
                                     PasswordEncoder encoder,
                                     Environment env,
                                     @Value("${monthley.superadmin.password:}") String pwEnv) {
        return args -> {
            String email = "superadmin@monthley.my";
            if (admins.existsByEmailIgnoreCase(email)) return;

            boolean dev = env.matchesProfiles("dev") || env.matchesProfiles("test");
            String pw = (pwEnv == null || pwEnv.isBlank()) ? null : pwEnv.trim();

            if (pw == null) {
                if (!dev) {
                    throw new IllegalStateException(
                            "MONTHLEY_SUPERADMIN_PASSWORD tidak ditetapkan. "
                            + "Pemasangan ini tiada superadmin dan tidak boleh mencipta "
                            + "satu dengan kata laluan lalai di luar pembangunan.");
                }
                pw = "superadmin";
            }
            if (pw.length() < 12 && !dev) {
                throw new IllegalStateException(
                        "MONTHLEY_SUPERADMIN_PASSWORD terlalu pendek (minimum 12 aksara).");
            }

            admins.save(new PlatformAdmin(
                    email, "Superadmin Monthley",
                    PlatformAdmin.Role.SUPERADMIN,
                    encoder.encode(pw)));

            // Kata laluan TIDAK dicetak. Log dibaca oleh sesiapa yang ada
            // akses pelayan, dan ia kekal dalam fail log selama-lamanya.
            System.out.println(">>> Superadmin dicipta: " + email);
        };
    }
}
