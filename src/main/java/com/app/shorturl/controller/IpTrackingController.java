package com.app.shorturl.controller;

import com.app.shorturl.model.ClickLog;
import com.app.shorturl.repository.ClickLogRepository;
import com.app.shorturl.service.TrackingTokenService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

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
 *
 * Pengamanan (supaya tidak bisa dipakai memanipulasi klik orang lain):
 *  - trackId = token HMAC (id + shortCode), bukan ID mentah → tidak bisa ditebak.
 *  - shortCode wajib & harus cocok dengan record.
 *  - Hanya boleh update dalam jendela waktu singkat setelah klik dibuat.
 *  - Hanya SEKALI per klik (client_geo_updated_at).
 *  - Nilai divalidasi & dipotong sesuai panjang kolom.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class IpTrackingController {

    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Za-z]{2,3}$");

    private final ClickLogRepository clickLogRepository;
    private final TrackingTokenService trackingTokenService;

    @Value("${app.tracking.update-window-seconds:600}")
    private long updateWindowSeconds;

    @PostMapping("/track-ip")
    @Transactional
    public ResponseEntity<Void> trackIp(@RequestBody IpTrackRequest req) {
        try {
            if (req == null || req.getTrackId() == null || req.getTrackId().isBlank()
                    || req.getShortCode() == null || req.getShortCode().isBlank()) {
                return ResponseEntity.ok().build();
            }

            Optional<Long> idOpt = trackingTokenService.verify(req.getTrackId().trim(), req.getShortCode().trim());
            if (idOpt.isEmpty()) {
                log.debug("Invalid track token");
                return ResponseEntity.ok().build();
            }

            Optional<ClickLog> opt = clickLogRepository.findById(idOpt.get());
            if (opt.isEmpty()) {
                return ResponseEntity.ok().build();
            }

            ClickLog logEntry = opt.get();

            // shortCode harus cocok (token juga sudah terikat ke shortCode).
            if (logEntry.getShortCode() == null || !logEntry.getShortCode().equals(req.getShortCode().trim())) {
                return ResponseEntity.ok().build();
            }
            // Sekali saja per klik.
            if (logEntry.getClientGeoUpdatedAt() != null) {
                return ResponseEntity.ok().build();
            }
            // Hanya dalam jendela waktu setelah klik dibuat.
            LocalDateTime created = logEntry.getCreatedAt();
            if (created == null || created.plusSeconds(Math.max(updateWindowSeconds, 1)).isBefore(LocalDateTime.now())) {
                return ResponseEntity.ok().build();
            }

            if (isValidPublicIp(req.getClientIp())) {
                logEntry.setIpAddress(req.getClientIp().trim());
            }
            if (notBlank(req.getCountry()))     logEntry.setCountry(clean(req.getCountry(), 80));
            if (notBlank(req.getCountryCode()) && COUNTRY_CODE.matcher(req.getCountryCode().trim()).matches()) {
                logEntry.setCountryCode(req.getCountryCode().trim().toUpperCase());
            }
            if (notBlank(req.getRegion()))      logEntry.setRegion(clean(req.getRegion(), 80));
            if (notBlank(req.getCity()))        logEntry.setCity(clean(req.getCity(), 80));
            if (notBlank(req.getIsp()))         logEntry.setIsp(clean(req.getIsp(), 80));

            logEntry.setClientGeoUpdatedAt(LocalDateTime.now());
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

    /** Hanya IP literal publik; tidak pernah melakukan DNS lookup. */
    private boolean isValidPublicIp(String ip) {
        if (ip == null) return false;
        String v = ip.trim();
        boolean literal = IPV4.matcher(v).matches() || (v.contains(":") && IPV6.matcher(v).matches());
        if (!literal) return false;
        try {
            InetAddress a = InetAddress.getByName(v); // aman: input sudah dipastikan literal IP
            return !(a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress()
                    || isUniqueLocalV6(a) || isCgnat(a));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC; // fc00::/7
    }

    private static boolean isCgnat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64.0.0/10
    }

    /** Buang karakter kontrol & potong sesuai panjang kolom. */
    private String clean(String s, int max) {
        if (s == null) return null;
        String v = s.replaceAll("\\p{Cntrl}", "").trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    @Data
    public static class IpTrackRequest {
        private String trackId;     // token bertanda tangan dari server (bukan ID mentah)
        private String shortCode;   // wajib, untuk validasi
        private String clientIp;
        private String country;
        private String countryCode;
        private String region;
        private String city;
        private String isp;
    }
}
