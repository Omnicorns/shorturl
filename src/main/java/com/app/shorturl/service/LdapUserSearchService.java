package com.app.shorturl.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Service;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import java.util.*;

/**
 * Pencarian user di Active Directory untuk autocomplete saat
 * mengelola akses short URL.
 *
 * Strategi: cari user yang sAMAccountName, mail, atau displayName
 * mengandung query string. Hasil dibatasi dan di-cache singkat di memori.
 *
 * Catatan keamanan:
 * - Endpoint pemanggil HARUS sudah authenticated (di-protect di SecurityConfig).
 * - Hanya field non-sensitif (username, display name, email) yang di-return.
 * - Anonymous bind dipakai kalau manager DN kosong (config existing).
 */
@Service
@Slf4j
public class LdapUserSearchService {

    @Autowired(required = false)
    private LdapTemplate ldapTemplate;

    @Autowired(required = false)
    private LdapContextSource ldapContextSource;

    @Value("${ldap.base-dn:}")
    private String baseDn;

    @Value("${ldap.user-search-base:}")
    private String userSearchBase;

    /** Hasil per query — minimal sAMAccountName, displayName, mail. */
    public record LdapUser(String username, String displayName, String email) {}

    // ─── Cache sederhana per-query untuk hindari spam ke AD ───
    private final Map<String, CachedResult> cache = new HashMap<>();
    private static final long CACHE_TTL_MS = 30_000;
    private static final int MAX_RESULTS = 15;

    private record CachedResult(long timestamp, List<LdapUser> users) {}

    /**
     * Cari user yang username/email/nama mengandung {@code q}.
     * Return list kosong kalau LDAP tidak tersedia atau q < 2 char.
     */
    public List<LdapUser> search(String q) {
        if (q == null) return List.of();
        String query = q.trim();
        if (query.length() < 2) return List.of();
        if (ldapTemplate == null) {
            log.debug("LDAP template not available, returning empty");
            return List.of();
        }

        String key = query.toLowerCase();
        CachedResult c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && (now - c.timestamp) < CACHE_TTL_MS) {
            return c.users;
        }

        try {
            // Escape karakter LDAP filter berbahaya
            String safe = escapeLdapFilter(query);
            String filter =
                "(&(objectCategory=person)(objectClass=user)" +
                "(|(sAMAccountName=*" + safe + "*)" +
                "  (mail=*" + safe + "*)" +
                "  (displayName=*" + safe + "*)" +
                "  (givenName=*" + safe + "*)" +
                "  (sn=*" + safe + "*)))";

            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setCountLimit(MAX_RESULTS);
            sc.setTimeLimit(3000); // 3 detik
            sc.setReturningAttributes(new String[]{"sAMAccountName", "displayName", "mail"});

            String searchBase = (userSearchBase != null && !userSearchBase.isBlank())
                    ? userSearchBase
                    : ""; // pakai base context dari ContextSource

            List<LdapUser> results = ldapTemplate.search(
                    searchBase,
                    filter,
                    sc,
                    new UserAttributesMapper()
            );

            // Filter null & deduplicate by username
            Map<String, LdapUser> dedup = new LinkedHashMap<>();
            for (LdapUser u : results) {
                if (u != null && u.username() != null && !u.username().isBlank()) {
                    dedup.putIfAbsent(u.username().toLowerCase(), u);
                }
            }
            List<LdapUser> finalList = new ArrayList<>(dedup.values());

            // Limit ke MAX_RESULTS
            if (finalList.size() > MAX_RESULTS) {
                finalList = finalList.subList(0, MAX_RESULTS);
            }

            // Cleanup cache lama (best-effort)
            if (cache.size() > 100) {
                cache.entrySet().removeIf(e -> (now - e.getValue().timestamp) > CACHE_TTL_MS);
            }
            cache.put(key, new CachedResult(now, finalList));

            log.debug("LDAP search '{}' returned {} users", query, finalList.size());
            return finalList;

        } catch (Exception e) {
            log.warn("LDAP search failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * Escape karakter berbahaya untuk LDAP filter (RFC 4515).
     */
    private static String escapeLdapFilter(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*'  -> sb.append("\\2a");
                case '('  -> sb.append("\\28");
                case ')'  -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Cek apakah LDAP tersedia & responsif. */
    public boolean isAvailable() {
        return ldapTemplate != null;
    }

    /**
     * Versi verbose dari search — return filter LDAP yang digunakan,
     * base DN, error message kalau ada, dan list user.
     * Dipakai oleh endpoint /admin/ldap/debug untuk troubleshoot.
     */
    public Map<String, Object> debugSearch(String q) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ldapAvailable", ldapTemplate != null);
        out.put("queryInput", q);

        if (ldapTemplate == null) {
            out.put("error", "LdapTemplate bean tidak ada — cek pom.xml & LdapContextConfig");
            return out;
        }

        String query = (q == null) ? "" : q.trim();
        if (query.length() < 1) query = "a"; // sample agar tetap query

        String safe = escapeLdapFilter(query);
        String filter =
            "(&(objectCategory=person)(objectClass=user)" +
            "(|(sAMAccountName=*" + safe + "*)" +
            "  (mail=*" + safe + "*)" +
            "  (displayName=*" + safe + "*)" +
            "  (givenName=*" + safe + "*)" +
            "  (sn=*" + safe + "*)))";

        out.put("baseDn", baseDn);
        out.put("userSearchBase", userSearchBase);
        out.put("ldapFilter", filter);

        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setCountLimit(MAX_RESULTS);
        sc.setTimeLimit(5000);
        sc.setReturningAttributes(new String[]{"sAMAccountName", "displayName", "mail",
                "userPrincipalName", "cn", "distinguishedName"});

        String searchBase = (userSearchBase != null && !userSearchBase.isBlank())
                ? userSearchBase : "";

        try {
            List<Map<String, Object>> raw = ldapTemplate.search(
                    searchBase,
                    filter,
                    sc,
                    (AttributesMapper<Map<String, Object>>) attrs -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        var ids = attrs.getIDs();
                        while (ids.hasMore()) {
                            String name = ids.next();
                            Attribute a = attrs.get(name);
                            try { m.put(name, a == null ? null : String.valueOf(a.get())); }
                            catch (Exception ex) { m.put(name, "<error: " + ex.getMessage() + ">"); }
                        }
                        return m;
                    }
            );
            out.put("count", raw.size());
            out.put("results", raw);
        } catch (Exception e) {
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            // Kasih hint berdasarkan error
            String msg = String.valueOf(e.getMessage()).toLowerCase();
            if (msg.contains("anonymous") || msg.contains("000004dc") || msg.contains("invalid credentials")) {
                out.put("hint", "AD menolak anonymous bind. Set ldap.manager-dn dan ldap.manager-password di application.properties (service account dengan permission READ ke OU users).");
            } else if (msg.contains("size") || msg.contains("limit exceeded")) {
                out.put("hint", "Hasil terlalu banyak. Buat query lebih spesifik atau set ldap.user-search-base ke OU tertentu.");
            } else if (msg.contains("naming") || msg.contains("partial") || msg.contains("referral")) {
                out.put("hint", "Ada masalah base DN atau referral. Pastikan ldap.base-dn benar (mis. DC=sarinah,DC=net).");
            } else if (msg.contains("connect") || msg.contains("timeout") || msg.contains("connection")) {
                out.put("hint", "Tidak bisa konek ke AD server. Cek ldap.url, firewall, dan apakah server hidup.");
            }
        }
        return out;
    }

    private static class UserAttributesMapper implements AttributesMapper<LdapUser> {
        @Override
        public LdapUser mapFromAttributes(Attributes attrs) {
            try {
                String username = getStr(attrs, "sAMAccountName");
                String display  = getStr(attrs, "displayName");
                String email    = getStr(attrs, "mail");
                if (username == null) return null;
                return new LdapUser(username.toLowerCase(), display, email);
            } catch (Exception e) {
                return null;
            }
        }
        private static String getStr(Attributes attrs, String name) throws Exception {
            Attribute a = attrs.get(name);
            if (a == null) return null;
            Object v = a.get();
            return v == null ? null : v.toString();
        }
    }
}
