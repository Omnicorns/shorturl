package com.app.shorturl.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Token tracking untuk /api/v1/track-ip.
 *
 * Sebelumnya browser mengirim ID ClickLog mentah (angka berurutan), sehingga
 * siapa pun bisa meng-update record klik milik orang lain dengan menebak ID.
 * Sekarang server memberi token bertanda tangan HMAC: {@code <id>.<signature>},
 * yang terikat ke id + shortCode dan tidak bisa dipalsukan tanpa secret server.
 */
@Slf4j
@Service
public class TrackingTokenService {

    private static final String ALGO = "HmacSHA256";
    private final byte[] secret;

    public TrackingTokenService(@Value("${app.tracking.secret:}") String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("app.tracking.secret belum di-set; memakai secret acak per-start. " +
                    "Set APP_TRACKING_SECRET supaya token tetap valid antar restart / antar instance.");
        } else {
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Buat token untuk ClickLog id + shortCode. Return null kalau id null. */
    public String sign(Long clickLogId, String shortCode) {
        if (clickLogId == null) return null;
        return clickLogId + "." + mac(clickLogId, shortCode);
    }

    /** Verifikasi token; return id ClickLog kalau tanda tangan cocok dengan shortCode. */
    public Optional<Long> verify(String token, String shortCode) {
        if (token == null || shortCode == null || shortCode.isBlank()) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || token.length() > 128) return Optional.empty();

        Long id;
        try {
            id = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        byte[] expected = mac(id, shortCode).getBytes(StandardCharsets.US_ASCII);
        byte[] given = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given) ? Optional.of(id) : Optional.empty();
    }

    private String mac(Long id, String shortCode) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret, ALGO));
            byte[] out = mac.doFinal((id + "|" + (shortCode == null ? "" : shortCode))
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC tidak tersedia", e);
        }
    }
}
