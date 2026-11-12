package com.app.shorturl.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Helper terpusat untuk akses kontrol Short URL.
 *
 * Aturan super-admin (bisa lihat & kelola SEMUA URL):
 *   1. User yang punya authority ROLE_SUPERADMIN, ATAU
 *   2. Username sama dengan {@code app.admin.username} (akun lokal in-memory).
 *      Ini menjaga kompatibilitas: akun `surl` yang sudah ada otomatis
 *      super-admin tanpa migrasi data.
 *   3. (Opsional) username yang di-list di properti {@code app.superadmins}
 *      (comma-separated, untuk user AD tertentu).
 *
 * Selain itu semua user (lokal/AD) adalah "regular admin": hanya melihat URL
 * yang dia buat atau yang dia di-add sebagai co-manager.
 */
@Component
public class AccessControlHelper {

    @Value("${app.admin.username:}")
    private String localAdminUsername;

    @Value("${app.superadmins:}")
    private String superAdminsCsv;

    /** Username current user (lowercase), atau null kalau anonymous. */
    public String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getName() != null ? auth.getName().toLowerCase() : null;
    }

    /** Apakah user yang sedang login adalah super-admin? */
    public boolean isCurrentUserSuperAdmin() {
        return isSuperAdmin(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Satu-satunya sumber kebenaran untuk status super-admin.
     * Dipakai juga oleh CatalogAccessService supaya aturannya tidak berbeda.
     */
    public boolean isSuperAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return false;
        }

        // 1) Authority eksplisit
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String role = ga.getAuthority();
            if ("ROLE_SUPERADMIN".equals(role) || "ROLE_SUPER_ADMIN".equals(role)) return true;
        }

        // 2) Username = local admin
        String name = auth.getName();
        if (name != null && localAdminUsername != null && !localAdminUsername.isBlank()
                && name.equalsIgnoreCase(localAdminUsername)) {
            return true;
        }

        // 3) Whitelist via properti
        if (name != null && superAdminsCsv != null && !superAdminsCsv.isBlank()) {
            for (String s : superAdminsCsv.split(",")) {
                if (s != null && s.trim().equalsIgnoreCase(name)) return true;
            }
        }
        return false;
    }

    /** Apakah username tercantum di app.superadmins (TIDAK termasuk akun emergency lokal). */
    public boolean isListedInSuperAdminProperty(String name) {
        if (name == null || name.isBlank() || superAdminsCsv == null || superAdminsCsv.isBlank()) return false;
        for (String s : superAdminsCsv.split(",")) {
            if (s != null && s.trim().equalsIgnoreCase(name.trim())) return true;
        }
        return false;
    }

    /** Set super-admin yang terdaftar di properti (lowercase) — untuk display. */
    public Set<String> configuredSuperAdmins() {
        Set<String> out = new HashSet<>();
        if (localAdminUsername != null && !localAdminUsername.isBlank()) {
            out.add(localAdminUsername.toLowerCase());
        }
        if (superAdminsCsv != null && !superAdminsCsv.isBlank()) {
            for (String s : superAdminsCsv.split(",")) {
                if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase());
            }
        }
        return out;
    }
}
