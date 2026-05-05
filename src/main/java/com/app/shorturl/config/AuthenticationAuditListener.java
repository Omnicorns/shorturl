package com.app.shorturl.config;

import com.app.shorturl.model.AuthLog;
import com.app.shorturl.service.AuthLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import jakarta.servlet.ServletException;

/**
 * Tangkap event authentication dari Spring Security:
 *
 *   - AuthenticationSuccessEvent          → LOGIN_SUCCESS
 *   - AbstractAuthenticationFailureEvent  → LOGIN_FAILURE
 *   - LogoutSuccessHandler                → LOGOUT (dipasang di SecurityConfig)
 *
 * Catatan: HttpSessionDestroyedEvent kita TIDAK pakai untuk logout
 * karena dia juga ke-trigger saat session timeout — kita hanya catat
 * logout eksplisit lewat LogoutSuccessHandler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationAuditListener implements LogoutSuccessHandler {

    private final AuthLogService authLogService;

    // ============================================================
    // Login SUCCESS
    // ============================================================
    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        String username = auth.getName();
        AuthLog.AuthSource source = detectSource(auth);
        HttpServletRequest req = currentRequest();
        String sessionId = req != null && req.getSession(false) != null
                ? req.getSession(false).getId() : null;

        authLogService.record(
                AuthLog.EventType.LOGIN_SUCCESS,
                source,
                username,
                sessionId,
                null,
                req
        );
    }

    // ============================================================
    // Login FAILURE (bad credentials, account locked, dst.)
    // ============================================================
    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String username = event.getAuthentication() != null
                ? String.valueOf(event.getAuthentication().getPrincipal())
                : "(unknown)";
        String reason = event.getException() != null
                ? event.getException().getClass().getSimpleName() + ": "
                  + event.getException().getMessage()
                : "unknown";

        authLogService.record(
                AuthLog.EventType.LOGIN_FAILURE,
                AuthLog.AuthSource.UNKNOWN,
                username,
                null,
                reason,
                currentRequest()
        );
    }

    // ============================================================
    // LOGOUT (dipanggil sebagai LogoutSuccessHandler)
    // ============================================================
    @Override
    public void onLogoutSuccess(HttpServletRequest request,
                                HttpServletResponse response,
                                Authentication authentication)
            throws IOException, ServletException {

        String username = authentication != null ? authentication.getName() : "(unknown)";
        AuthLog.AuthSource source = authentication != null
                ? detectSource(authentication) : AuthLog.AuthSource.UNKNOWN;
        String sessionId = request.getSession(false) != null
                ? request.getSession(false).getId() : null;

        authLogService.record(
                AuthLog.EventType.LOGOUT,
                source,
                username,
                sessionId,
                null,
                request
        );

        // redirect manual karena kita override default LogoutSuccessHandler
        response.sendRedirect(request.getContextPath() + "/");
    }

    // ============================================================
    // Helper
    // ============================================================
    private AuthLog.AuthSource detectSource(Authentication auth) {
        if (auth == null) return AuthLog.AuthSource.UNKNOWN;
        String principalClass = auth.getPrincipal() != null
                ? auth.getPrincipal().getClass().getName() : "";
        // User AD biasanya principal-nya berupa String (UPN) atau LdapUserDetails
        if (principalClass.toLowerCase().contains("ldap")
                || principalClass.contains("ActiveDirectory")) {
            return AuthLog.AuthSource.LDAP;
        }
        // ActiveDirectoryLdapAuthenticationProvider sering set principal sebagai String email
        if (auth.getPrincipal() instanceof String s && s.contains("@")) {
            return AuthLog.AuthSource.LDAP;
        }
        return AuthLog.AuthSource.LOCAL;
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception ex) {
            return null;
        }
    }
}