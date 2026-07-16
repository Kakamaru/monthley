package com.monthley.identity.internal;

import com.monthley.notification.api.EmailPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/** Jana token & hantar e-mel pengesahan / reset. */
@Service
class VerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserTokenRepository tokens;
    private final EmailPort email;
    private final String appUrl;

    VerificationService(UserTokenRepository tokens, EmailPort email,
                        @Value("${monthley.app-url:http://localhost:4200}") String appUrl) {
        this.tokens = tokens;
        this.email = email;
        this.appUrl = appUrl;
    }

    @Transactional
    public void sendVerification(AppUser user) {
        tokens.invalidatePrevious(user.getId(), UserToken.Type.VERIFY_EMAIL);
        String token = newToken();
        tokens.save(new UserToken(user.getId(), token,
                UserToken.Type.VERIFY_EMAIL, LocalDateTime.now().plusHours(24)));
        email.sendVerification(user.getEmail(), user.getFullName(),
                appUrl + "/verify?token=" + token);
    }

    @Transactional
    public void sendPasswordReset(AppUser user) {
        tokens.invalidatePrevious(user.getId(), UserToken.Type.RESET_PASSWORD);
        String token = newToken();
        tokens.save(new UserToken(user.getId(), token,
                UserToken.Type.RESET_PASSWORD, LocalDateTime.now().plusHours(1)));
        email.sendPasswordReset(user.getEmail(), user.getFullName(),
                appUrl + "/reset?token=" + token);
    }

    public void sendWelcome(AppUser user) {
        email.sendWelcome(user.getEmail(), user.getFullName(), appUrl + "/portal");
    }

    private static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
