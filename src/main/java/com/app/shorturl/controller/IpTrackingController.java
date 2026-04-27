package com.app.shorturl.controller;

import com.app.shorturl.model.ClickLog;
import com.app.shorturl.repository.ClickLogRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Endpoint untuk update IP & geolocation dari sisi browser user.
 *
 * Latar belakang:
 * VM provider melakukan NAT yang membuat IP user di-rewrite jadi IP
 * internal (mis. 10.0.0.1) sebelum sampai ke Nginx/Spring Boot. Akibatnya,
 * dari sisi server kita tidak bisa lihat IP asli user.
 *
 * Workaround: browser user (yang masih punya IP utuh) memanggil API publik
 * (ipapi.co) untuk dapatkan IP + lokasi-nya sendiri, lalu mengirimkan data
 * itu ke endpoint ini untuk meng-update record ClickLog yang sudah dibuat
 * sebelumnya saat halaman preview di-render.
 *
 * Catatan: data ini "best effort" dan bisa dimanipulasi oleh user. Cocok
 * untuk analytics, TIDAK cocok untuk audit/security.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class IpTrackingController {

    private final ClickLogRepository clickLogRepository;

    @PostMapping("/track-ip")
    @Transactional
    public ResponseEntity<Void> trackIp(@RequestBody IpTrackRequest req) {
        try {
            if (req == null || req.getTrackId() == null || req.getTrackId().isBlank()) {
                return ResponseEntity.ok().build();
            }

            Long id;
            try {
                id = Long.parseLong(req.getTrackId());
            } catch (NumberFormatException e) {
                log.debug("Invalid trackId format: {}", req.getTrackId());
                return ResponseEntity.ok().build();
            }

            Optional<ClickLog> opt = clickLogRepository.findById(id);
            if (opt.isEmpty()) {
                log.debug("ClickLog id {} not found", id);
                return ResponseEntity.ok().build();
            }

            ClickLog logEntry = opt.get();

            // Validasi: shortCode di request harus cocok dengan record,
            // mencegah user iseng update log orang lain.
            if (req.getShortCode() != null
                    && logEntry.getShortCode() != null
                    && !req.getShortCode().equals(logEntry.getShortCode())) {
                log.debug("shortCode mismatch for id {}", id);
                return ResponseEntity.ok().build();
            }

            // Update IP dan geolocation hanya kalau data dari client masuk akal
            if (isValidPublicIp(req.getClientIp())) {
                logEntry.setIpAddress(req.getClientIp());
            }
            if (notBlank(req.getCountry()))     logEntry.setCountry(req.getCountry());
            if (notBlank(req.getCountryCode())) logEntry.setCountryCode(req.getCountryCode());
            if (notBlank(req.getRegion()))      logEntry.setRegion(req.getRegion());
            if (notBlank(req.getCity()))        logEntry.setCity(req.getCity());
            if (notBlank(req.getIsp()))         logEntry.setIsp(truncate(req.getIsp(), 80));

            clickLogRepository.save(logEntry);
        } catch (Exception e) {
            log.warn("trackIp failed: {}", e.getMessage());
        }
        // Selalu return 200 — kita tidak mau client tahu detail kegagalan
        return ResponseEntity.ok().build();
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private boolean isValidPublicIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        // tolak IP private/loopback yang dikirim dari client (mungkin nyasar)
        return !ip.startsWith("10.")
                && !ip.startsWith("192.168.")
                && !ip.startsWith("172.16.") && !ip.startsWith("172.17.")
                && !ip.startsWith("172.18.") && !ip.startsWith("172.19.")
                && !ip.startsWith("172.2")   && !ip.startsWith("172.30.")
                && !ip.startsWith("172.31.")
                && !ip.equals("127.0.0.1")
                && !ip.equals("::1")
                && !ip.startsWith("0:");
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    @Data
    public static class IpTrackRequest {
        private String trackId;     // id ClickLog yang akan di-update
        private String shortCode;   // untuk validasi
        private String clientIp;
        private String country;
        private String countryCode;
        private String region;
        private String city;
        private String isp;
    }
}