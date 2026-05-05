package com.app.shorturl.service;

import com.app.shorturl.model.AuthLog;
import com.app.shorturl.repository.AuthLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthLogService {

    private final AuthLogRepository repo;

    /**
     * Tulis ke DB secara async biar nggak nge-block proses login/logout.
     * AsyncConfig sudah ada di project, jadi @Async langsung jalan.
     */
    @Async
    public void record(AuthLog.EventType eventType,
                       AuthLog.AuthSource source,
                       String username,
                       String sessionId,
                       String failureReason,
                       HttpServletRequest request) {
        try {
            AuthLog entry = AuthLog.builder()
                    .username(username == null ? "(unknown)" : username)
                    .eventType(eventType)
                    .authSource(source == null ? AuthLog.AuthSource.UNKNOWN : source)
                    .ipAddress(extractIp(request))
                    .userAgent(extractUserAgent(request))
                    .failureReason(truncate(failureReason, 255))
                    .sessionId(sessionId)
                    .build();
            repo.save(entry);

            log.info("[AUTH-AUDIT] {} user='{}' source={} ip={} reason={}",
                    eventType, username, source, entry.getIpAddress(),
                    failureReason != null ? failureReason : "-");
        } catch (Exception ex) {
            // jangan sampai error logging meledakkan flow login
            log.error("Failed to write auth log", ex);
        }
    }

    private String extractIp(HttpServletRequest req) {
        if (req == null) return null;
        // X-Forwarded-For sudah ditangani Tomcat RemoteIpValve di properties,
        // jadi getRemoteAddr() sudah balikin client IP yang benar.
        String ip = req.getRemoteAddr();
        return truncate(ip, 45);
    }

    private String extractUserAgent(HttpServletRequest req) {
        if (req == null) return null;
        return truncate(req.getHeader("User-Agent"), 500);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}