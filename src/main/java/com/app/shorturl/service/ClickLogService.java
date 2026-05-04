package com.app.shorturl.service;

import com.app.shorturl.model.ClickLog;
import com.app.shorturl.repository.ClickLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Set;

// ═══════════════════════════════════════════════════════════════════
//  ClickLogService.java — TAMBAHAN deteksi source dari Referer
// ═══════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════
//  ClickLogService.java — DETEKSI QR via query param ?src=qr
//  + tetap fallback ke Referer untuk klik biasa
// ═══════════════════════════════════════════════════════════════════

@Service
@Slf4j
@RequiredArgsConstructor
public class ClickLogService {

    private final ClickLogRepository clickLogRepository;

    @Value("${app.log.geolocation-enabled:true}")
    private boolean geolocationEnabled;

    private final UserAgentAnalyzer uaAnalyzer = UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .withCache(1000)
            .build();

    private final RestTemplate restTemplate = new RestTemplate();

    @Getter
    @Builder
    public static class RequestSnapshot {
        private String ipAddress;
        private String userAgent;
        private String referer;
        private String srcParam;   // ← BARU: nilai query param ?src=...
    }

    /**
     * Baca snapshot dari request — sekarang juga ambil query param "src"
     * untuk deteksi QR / source manual lainnya.
     */
    public static RequestSnapshot snapshot(HttpServletRequest request) {
        return RequestSnapshot.builder()
                .ipAddress(extractClientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .referer(request.getHeader("Referer"))
                .srcParam(request.getParameter("src"))   // ← BARU
                .build();
    }

    private ClickLog buildClickLog(Long shortUrlId, String shortCode, RequestSnapshot snap) {
        String ip = snap.getIpAddress();
        String userAgent = snap.getUserAgent();
        String referer = snap.getReferer();

        ClickLog.ClickLogBuilder builder = ClickLog.builder()
                .shortUrlId(shortUrlId)
                .shortCode(shortCode)
                .ipAddress(ip)
                .userAgent(truncate(userAgent, 500))
                .referer(truncate(referer, 500))
                // ─── Prioritas: query param > UA in-app > referer domain ───
                .clickSource(detectClickSource(snap.getSrcParam(), referer, userAgent));

        if (userAgent != null && !userAgent.isBlank()) {
            parseUserAgent(userAgent, builder);
        }

        if (geolocationEnabled && ip != null && !isPrivateIp(ip)) {
            enrichWithGeolocation(ip, builder);
        }

        return builder.build();
    }

    @Async("logExecutor")
    public void logClick(Long shortUrlId, String shortCode, RequestSnapshot snap) {
        try {
            clickLogRepository.save(buildClickLog(shortUrlId, shortCode, snap));
        } catch (Exception e) {
            log.warn("Gagal log click untuk {}: {}", shortCode, e.getMessage());
        }
    }

    public Long logClickAndReturnId(Long shortUrlId, String shortCode, RequestSnapshot snap) {
        try {
            ClickLog saved = clickLogRepository.save(buildClickLog(shortUrlId, shortCode, snap));
            return saved.getId();
        } catch (Exception e) {
            log.warn("Gagal log click sync untuk {}: {}", shortCode, e.getMessage());
            return null;
        }
    }

    @Deprecated
    public void logClick(Long shortUrlId, String shortCode, HttpServletRequest request) {
        RequestSnapshot snap = snapshot(request);
        logClick(shortUrlId, shortCode, snap);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Deteksi source dengan PRIORITAS:
    //  1. ?src=qr / ?src=poster / dll  → eksplisit, paling akurat
    //  2. User-Agent in-app browser    → FB/IG/WA app
    //  3. Referer domain               → google/facebook/etc
    //  4. Default                       → DIRECT atau REFERRAL
    // ═══════════════════════════════════════════════════════════════

    /**
     * Whitelist nilai ?src=... yang valid. Mencegah orang iseng append
     * ?src=hack untuk pollute statistik.
     */
    private static final Set<String> ALLOWED_SRC = Set.of(
            "qr",         // QR code di poster/spanduk/brosur
            "poster",     // generic poster
            "email",      // dari email campaign
            "sms",        // dari SMS broadcast
            "wa",         // dari WhatsApp broadcast (manual)
            "ig",         // dari Instagram bio
            "fb",         // dari Facebook post
            "tiktok",     // dari TikTok bio
            "ads",        // dari iklan berbayar
            "print"       // dari media cetak
    );

    public static String detectClickSource(String srcParam, String referer, String userAgent) {

        // ─── 1. Query param eksplisit (paling akurat) ────────────
        if (srcParam != null && !srcParam.isBlank()) {
            String src = srcParam.trim().toLowerCase();
            if (ALLOWED_SRC.contains(src)) {
                return src.toUpperCase();      // "qr" → "QR"
            }
            // Kalau nilainya gak di whitelist, abaikan & lanjut deteksi normal
        }

        // ─── 2. Tidak ada referer → cek UA in-app ────────────────
        if (referer == null || referer.isBlank()) {
            String fromUa = detectFromUserAgent(userAgent);
            if (fromUa != null) return fromUa;
            return "DIRECT"; // ketik manual / bookmark / scan QR tanpa marker
        }

        // ─── 3. Parse referer ────────────────────────────────────
        String host;
        try {
            host = new java.net.URI(referer).getHost();
            if (host == null) return "REFERRAL";
            host = host.toLowerCase();
            if (host.startsWith("www.")) host = host.substring(4);
            if (host.startsWith("m."))   host = host.substring(2);
        } catch (Exception e) {
            return "REFERRAL";
        }

        // Search engines
        if (host.contains("google."))      return "GOOGLE";
        if (host.contains("bing.com"))     return "BING";
        if (host.contains("duckduckgo."))  return "DUCKDUCKGO";
        if (host.contains("yahoo."))       return "YAHOO";
        if (host.contains("yandex."))      return "YANDEX";

        // Social media
        if (host.contains("facebook.") || host.equals("fb.com") || host.contains("fb.me"))
            return "FACEBOOK";
        if (host.contains("instagram."))   return "INSTAGRAM";
        if (host.contains("twitter.") || host.equals("x.com") || host.equals("t.co"))
            return "TWITTER";
        if (host.contains("tiktok."))      return "TIKTOK";
        if (host.contains("linkedin.") || host.equals("lnkd.in"))
            return "LINKEDIN";
        if (host.contains("youtube.") || host.equals("youtu.be"))
            return "YOUTUBE";
        if (host.contains("reddit."))      return "REDDIT";
        if (host.contains("pinterest."))   return "PINTEREST";

        // Chat / messenger
        if (host.contains("whatsapp.") || host.equals("wa.me") || host.contains("chat.whatsapp"))
            return "WHATSAPP";
        if (host.contains("telegram.") || host.equals("t.me"))
            return "TELEGRAM";
        if (host.contains("line.me") || host.contains("line.naver"))
            return "LINE";
        if (host.contains("messenger.") || host.equals("m.me"))
            return "MESSENGER";
        if (host.contains("slack."))       return "SLACK";
        if (host.contains("discord."))     return "DISCORD";

        // Email web
        if (host.contains("mail.google") || host.contains("gmail.")
                || host.contains("outlook.") || host.contains("mail.yahoo")
                || host.contains("mail.live"))
            return "EMAIL";

        // Internal — ganti "surl.co.id" dengan domain Anda
        if (host.endsWith("surl.co.id"))   return "INTERNAL";

        return "REFERRAL";
    }

    private static String detectFromUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return null;
        String s = ua.toLowerCase();

        if (s.contains("fbav") || s.contains("fban"))  return "FACEBOOK";
        if (s.contains("instagram"))                    return "INSTAGRAM";
        if (s.contains("line/"))                        return "LINE";
        if (s.contains("twitter"))                      return "TWITTER";
        if (s.contains("tiktok"))                       return "TIKTOK";
        if (s.contains("whatsapp"))                     return "WHATSAPP";
        if (s.contains("telegram"))                     return "TELEGRAM";
        if (s.contains("linkedin"))                     return "LINKEDIN";
        if (s.contains("micromessenger"))               return "WECHAT";

        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Sisanya = kode lama, tidak berubah
    // ═══════════════════════════════════════════════════════════════

    private void parseUserAgent(String ua, ClickLog.ClickLogBuilder builder) {
        try {
            UserAgent agent = uaAnalyzer.parse(ua);
            builder.browser(safe(agent.getValue(UserAgent.AGENT_NAME)))
                    .browserVersion(safe(agent.getValue(UserAgent.AGENT_VERSION_MAJOR)))
                    .operatingSystem(safe(agent.getValue(UserAgent.OPERATING_SYSTEM_NAME_VERSION)))
                    .deviceType(safe(agent.getValue(UserAgent.DEVICE_CLASS)))
                    .deviceBrand(safe(agent.getValue(UserAgent.DEVICE_BRAND)));
        } catch (Exception e) {
            log.debug("UA parse gagal: {}", e.getMessage());
        }
    }

    private void enrichWithGeolocation(String ip, ClickLog.ClickLogBuilder builder) {
        try {
            String url = "http://ip-api.com/json/" + ip
                    + "?fields=status,country,countryCode,regionName,city,isp";
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);

            if (resp != null && "success".equals(resp.get("status"))) {
                builder.country(str(resp.get("country")))
                        .countryCode(str(resp.get("countryCode")))
                        .region(str(resp.get("regionName")))
                        .city(str(resp.get("city")))
                        .isp(truncate(str(resp.get("isp")), 80));
            }
        } catch (Exception e) {
            log.debug("Geolocation gagal untuk {}: {}", ip, e.getMessage());
        }
    }

    private static String extractClientIp(HttpServletRequest request) {
        String[] headers = {
                "X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP",
                "True-Client-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"
        };
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                return value.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private boolean isPrivateIp(String ip) {
        return ip.startsWith("10.")
                || ip.startsWith("192.168.")
                || ip.startsWith("172.16.") || ip.startsWith("172.17.")
                || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                || ip.startsWith("172.2")   || ip.startsWith("172.30.")
                || ip.startsWith("172.31.")
                || ip.equals("127.0.0.1")
                || ip.equals("0:0:0:0:0:0:0:1")
                || ip.equals("::1")
                || ip.startsWith("0:0:0:0");
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String safe(String s) {
        if (s == null || s.isBlank() || "Unknown".equalsIgnoreCase(s)
                || "??".equals(s) || s.startsWith("Unknown")) {
            return null;
        }
        return s;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}