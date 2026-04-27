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
    }

    public static RequestSnapshot snapshot(HttpServletRequest request) {
        return RequestSnapshot.builder()
                .ipAddress(extractClientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .referer(request.getHeader("Referer"))
                .build();
    }

    /**
     * Build ClickLog dari snapshot — extract logic supaya bisa dipakai
     * baik dari method async maupun synchronous.
     */
    private ClickLog buildClickLog(Long shortUrlId, String shortCode, RequestSnapshot snap) {
        String ip = snap.getIpAddress();
        String userAgent = snap.getUserAgent();
        String referer = snap.getReferer();

        ClickLog.ClickLogBuilder builder = ClickLog.builder()
                .shortUrlId(shortUrlId)
                .shortCode(shortCode)
                .ipAddress(ip)
                .userAgent(truncate(userAgent, 500))
                .referer(truncate(referer, 500));

        if (userAgent != null && !userAgent.isBlank()) {
            parseUserAgent(userAgent, builder);
        }

        if (geolocationEnabled && ip != null && !isPrivateIp(ip)) {
            enrichWithGeolocation(ip, builder);
        }

        return builder.build();
    }

    /**
     * Async version — untuk redirect biasa, tidak butuh ID kembali.
     */
    @Async("logExecutor")
    public void logClick(Long shortUrlId, String shortCode, RequestSnapshot snap) {
        try {
            clickLogRepository.save(buildClickLog(shortUrlId, shortCode, snap));
        } catch (Exception e) {
            log.warn("Gagal log click untuk {}: {}", shortCode, e.getMessage());
        }
    }

    /**
     * Synchronous version — untuk halaman preview, di mana kita butuh ID-nya
     * supaya bisa di-update belakangan dari sisi browser (IP tracking).
     *
     * Return null kalau gagal save.
     */
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