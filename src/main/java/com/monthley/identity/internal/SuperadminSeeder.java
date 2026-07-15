package com.monthley.identity.internal;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Cipta superadmin lalai jika belum wujud.
 * Kredential dev: superadmin@monthley.my / superadmin
 */
@Configuration
class SuperadminSeeder {

    @Bean
    ApplicationRunner seedSuperadmin(PlatformAdminRepository admins, PasswordEncoder encoder) {
        return args -> {
            String email = "superadmin@monthley.my";
            if (admins.existsByEmailIgnoreCase(email)) return;
            admins.save(new PlatformAdmin(
                    email, "Superadmin Monthley",
                    PlatformAdmin.Role.SUPERADMIN,
                    encoder.encode("superadmin")));
            System.out.println(">>> Superadmin dicipta: " + email + " / superadmin");
        };
    }
}
