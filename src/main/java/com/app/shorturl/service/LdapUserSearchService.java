package com.app.shorturl.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Service;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import java.util.*;

@Service
@Slf4j
public class LdapUserSearchService {

    @Autowired(required = false)
    private LdapTemplate ldapTemplate;

    @Autowired(required = false)
    private LdapContextSource ldapContextSource;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Value("${ldap.base-dn:}")
    private String baseDn;

    @Value("${ldap.user-search-base:}")
    private String userSearchBase;

    /**
     * Tambah source:
     * LOCAL = user hasil register app_users
     * LDAP  = user dari Active Directory
     * LOCAL+LDAP = username sama ditemukan di keduanya
     */
    public record LdapUser(String username, String displayName, String email, String source) {}

    private final Map<String, CachedResult> cache = new HashMap<>();

    private static final long CACHE_TTL_MS = 30_000;
    private static final int MAX_RESULTS = 20;

    private record CachedResult(long timestamp, List<LdapUser> users) {}

    /**
     * Cari user dari LOCAL + LDAP.
     * Dipakai oleh endpoint:
     * GET /admin/ldap/search?q=...
     */
    public List<LdapUser> search(String q) {
        if (q == null) return List.of();

        String query = q.trim();
        if (query.length() < 2) return List.of();

        String key = query.toLowerCase();
        long now = System.currentTimeMillis();

        CachedResult cached = cache.get(key);
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.users;
        }

        List<LdapUser> merged = new ArrayList<>();

        // 1. Search user lokal dari table app_users
        try {
            merged.addAll(searchLocalUsers(query));
        } catch (Exception e) {
            log.warn("Local user search failed for '{}': {}", query, e.getMessage());
        }

        // 2. Search user LDAP/AD
        try {
            merged.addAll(searchLdapUsers(query));
        } catch (Exception e) {
            log.warn("LDAP search failed for '{}': {}", query, e.getMessage());
        }

        List<LdapUser> finalList = mergeUsers(merged);

        if (cache.size() > 100) {
            cache.entrySet().removeIf(e -> (now - e.getValue().timestamp) > CACHE_TTL_MS);
        }

        cache.put(key, new CachedResult(now, finalList));

        return finalList;
    }

    /**
     * Search user lokal dari table app_users.
     * Cocok untuk user yang dibuat dari halaman Register.
     */
    private List<LdapUser> searchLocalUsers(String query) {
        if (jdbcTemplate == null) {
            log.debug("JdbcTemplate not available, skip local user search");
            return List.of();
        }

        String like = "%" + query.toLowerCase() + "%";

        // Versi utama: kalau app_users punya kolom full_name.
        String sqlWithFullName = """
                SELECT username, full_name, email
                FROM app_users
                WHERE LOWER(username) LIKE ?
                   OR LOWER(COALESCE(full_name, '')) LIKE ?
                   OR LOWER(COALESCE(email, '')) LIKE ?
                ORDER BY username ASC
                LIMIT ?
                """;

        try {
            return jdbcTemplate.query(sqlWithFullName, (rs, rowNum) -> new LdapUser(
                    safeLower(rs.getString("username")),
                    safe(rs.getString("full_name")),
                    safe(rs.getString("email")),
                    "LOCAL"
            ), like, like, like, MAX_RESULTS);
        } catch (Exception e) {
            log.debug("Local search with full_name failed, fallback to username/email only: {}", e.getMessage());
        }

        // Fallback: kalau kolom full_name belum ada.
        String sqlSimple = """
                SELECT username, email
                FROM app_users
                WHERE LOWER(username) LIKE ?
                   OR LOWER(COALESCE(email, '')) LIKE ?
                ORDER BY username ASC
                LIMIT ?
                """;

        return jdbcTemplate.query(sqlSimple, (rs, rowNum) -> {
            String username = safeLower(rs.getString("username"));
            return new LdapUser(
                    username,
                    username,
                    safe(rs.getString("email")),
                    "LOCAL"
            );
        }, like, like, MAX_RESULTS);
    }

    /**
     * Search user dari LDAP/Active Directory.
     */
    private List<LdapUser> searchLdapUsers(String query) {
        if (ldapTemplate == null) {
            log.debug("LDAP template not available, skip LDAP search");
            return List.of();
        }

        String safe = escapeLdapFilter(query);

        String filter =
                "(&(objectCategory=person)(objectClass=user)" +
                        "(|" +
                        "(sAMAccountName=*" + safe + "*)" +
                        "(mail=*" + safe + "*)" +
                        "(displayName=*" + safe + "*)" +
                        "(givenName=*" + safe + "*)" +
                        "(sn=*" + safe + "*)" +
                        "))";

        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setCountLimit(MAX_RESULTS);
        sc.setTimeLimit(3000);
        sc.setReturningAttributes(new String[]{
                "sAMAccountName",
                "displayName",
                "mail"
        });

        String searchBase = (userSearchBase != null && !userSearchBase.isBlank())
                ? userSearchBase
                : "";

        List<LdapUser> results = ldapTemplate.search(
                searchBase,
                filter,
                sc,
                new UserAttributesMapper()
        );

        log.debug("LDAP search '{}' returned {} users", query, results.size());

        return results;
    }

    /**
     * Merge duplicate username dari LOCAL dan LDAP.
     */
    private List<LdapUser> mergeUsers(List<LdapUser> users) {
        Map<String, LdapUser> dedup = new LinkedHashMap<>();

        for (LdapUser user : users) {
            if (user == null || user.username() == null || user.username().isBlank()) {
                continue;
            }

            String key = user.username().toLowerCase();

            if (!dedup.containsKey(key)) {
                dedup.put(key, user);
                continue;
            }

            LdapUser existing = dedup.get(key);

            String displayName = firstNotBlank(existing.displayName(), user.displayName());
            String email = firstNotBlank(existing.email(), user.email());

            String source = existing.source();
            if (!Objects.equals(existing.source(), user.source())) {
                source = "LOCAL+LDAP";
            }

            dedup.put(key, new LdapUser(
                    existing.username(),
                    displayName,
                    email,
                    source
            ));
        }

        List<LdapUser> finalList = new ArrayList<>(dedup.values());

        finalList.sort(Comparator.comparing(LdapUser::username));

        if (finalList.size() > MAX_RESULTS) {
            return finalList.subList(0, MAX_RESULTS);
        }

        return finalList;
    }

    /**
     * Untuk response controller.
     * Dibuat true kalau local user search atau LDAP tersedia.
     */
    public boolean isAvailable() {
        return jdbcTemplate != null || ldapTemplate != null;
    }

    /**
     * Endpoint debug:
     * GET /admin/ldap/debug?q=...
     */
    public Map<String, Object> debugSearch(String q) {
        Map<String, Object> out = new LinkedHashMap<>();

        String query = (q == null || q.trim().isBlank()) ? "a" : q.trim();

        out.put("queryInput", q);
        out.put("queryUsed", query);
        out.put("localAvailable", jdbcTemplate != null);
        out.put("ldapAvailable", ldapTemplate != null);
        out.put("baseDn", baseDn);
        out.put("userSearchBase", userSearchBase);

        try {
            List<LdapUser> localUsers = searchLocalUsers(query);
            out.put("localCount", localUsers.size());
            out.put("localUsers", localUsers);
        } catch (Exception e) {
            out.put("localError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        if (ldapTemplate == null) {
            out.put("ldapError", "LdapTemplate bean tidak ada — cek pom.xml dan LdapContextConfig");
        } else {
            String safe = escapeLdapFilter(query);

            String filter =
                    "(&(objectCategory=person)(objectClass=user)" +
                            "(|" +
                            "(sAMAccountName=*" + safe + "*)" +
                            "(mail=*" + safe + "*)" +
                            "(displayName=*" + safe + "*)" +
                            "(givenName=*" + safe + "*)" +
                            "(sn=*" + safe + "*)" +
                            "))";

            out.put("ldapFilter", filter);

            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setCountLimit(MAX_RESULTS);
            sc.setTimeLimit(5000);
            sc.setReturningAttributes(new String[]{
                    "sAMAccountName",
                    "displayName",
                    "mail",
                    "userPrincipalName",
                    "cn",
                    "distinguishedName"
            });

            String searchBase = (userSearchBase != null && !userSearchBase.isBlank())
                    ? userSearchBase
                    : "";

            try {
                List<Map<String, Object>> raw = ldapTemplate.search(
                        searchBase,
                        filter,
                        sc,
                        (AttributesMapper<Map<String, Object>>) attrs -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            try {
                                NamingEnumerationMapper.copyAttributes(attrs, m);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                            return m;
                        }
                );

                out.put("ldapCount", raw.size());
                out.put("ldapUsersRaw", raw);
            } catch (Exception e) {
                out.put("ldapError", e.getClass().getSimpleName() + ": " + e.getMessage());

                String msg = String.valueOf(e.getMessage()).toLowerCase();

                if (msg.contains("anonymous") || msg.contains("000004dc") || msg.contains("invalid credentials")) {
                    out.put("hint", "AD menolak anonymous bind. Set ldap.manager-dn dan ldap.manager-password di application.properties.");
                } else if (msg.contains("size") || msg.contains("limit exceeded")) {
                    out.put("hint", "Hasil terlalu banyak. Buat query lebih spesifik atau set ldap.user-search-base ke OU tertentu.");
                } else if (msg.contains("referral") || msg.contains("partial")) {
                    out.put("hint", "Ada masalah referral/base DN. Pastikan ldap.base-dn benar, contoh DC=sarinah,DC=net.");
                } else if (msg.contains("connect") || msg.contains("timeout") || msg.contains("connection")) {
                    out.put("hint", "Tidak bisa konek ke AD. Cek ldap.url, firewall, dan koneksi server.");
                }
            }
        }

        try {
            List<LdapUser> merged = search(query);
            out.put("mergedCount", merged.size());
            out.put("mergedUsers", merged);
        } catch (Exception e) {
            out.put("mergedError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return out;
    }

    private static String escapeLdapFilter(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }

        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String firstNotBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return "";
    }

    private static class UserAttributesMapper implements AttributesMapper<LdapUser> {
        @Override
        public LdapUser mapFromAttributes(Attributes attrs) {
            try {
                String username = getStr(attrs, "sAMAccountName");
                String display = getStr(attrs, "displayName");
                String email = getStr(attrs, "mail");

                if (username == null || username.isBlank()) {
                    return null;
                }

                return new LdapUser(
                        username.toLowerCase(),
                        display,
                        email,
                        "LDAP"
                );
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

    private static class NamingEnumerationMapper {
        static void copyAttributes(Attributes attrs, Map<String, Object> target) throws Exception {
            var ids = attrs.getIDs();

            while (ids.hasMore()) {
                String name = ids.next();
                Attribute a = attrs.get(name);

                try {
                    target.put(name, a == null ? null : String.valueOf(a.get()));
                } catch (Exception ex) {
                    target.put(name, "<error: " + ex.getMessage() + ">");
                }
            }
        }
    }
}