package com.app.shorturl.service;

import com.app.shorturl.model.ClickLog;
import com.app.shorturl.repository.ClickLogRepository;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * Simpan log async supaya tidak blocking redirect ke URL tujuan.
     */
    @Async("logExecutor")
    public void logClick(Long shortUrlId, String shortCode, HttpServletRequest request) {
        try {
            String ip = extractClientIp(request);
            String userAgent = request.getHeader("User-Agent");
            String referer = request.getHeader("Referer");

            ClickLog.ClickLogBuilder builder = ClickLog.builder()
                    .shortUrlId(shortUrlId)
                    .shortCode(shortCode)
                    .ipAddress(ip)
                    .userAgent(truncate(userAgent, 500))
                    .referer(truncate(referer, 500));

            // Parse User-Agent
            if (userAgent != null && !userAgent.isBlank()) {
                parseUserAgent(userAgent, builder);
            }

            // Geolocation
            if (geolocationEnabled && ip != null && !isPrivateIp(ip)) {
                enrichWithGeolocation(ip, builder);
            }

            clickLogRepository.save(builder.build());
        } catch (Exception e) {
            log.warn("Gagal log click untuk {}: {}", shortCode, e.getMessage());
        }
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
            // ip-api.com gratis untuk non-komersial, 45 req/menit
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

    /**
     * Ambil IP asli client dengan mempertimbangkan proxy/load balancer.
     */
    private String extractClientIp(HttpServletRequest request) {
        String[] headers = {
            "X-Forwarded-For",
            "X-Real-IP",
            "CF-Connecting-IP",      // Cloudflare
            "True-Client-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP"
        };
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                // X-Forwarded-For bisa berisi multiple IP, ambil yang pertama
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
            || ip.startsWith("172.2") || ip.startsWith("172.30.") || ip.startsWith("172.31.")
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
