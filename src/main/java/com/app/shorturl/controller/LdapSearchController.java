package com.app.shorturl.controller;

import com.app.shorturl.service.LdapUserSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Endpoint untuk autocomplete user dari LDAP/AD.
 *
 * Path: GET /admin/ldap/search?q=...
 *
 * Hanya dipakai oleh modal "Kelola Akses" di dashboard.
 * Karena di bawah /admin/**, otomatis ter-protect SecurityConfig
 * (perlu authentication).
 */
@RestController
@RequestMapping("/admin/ldap")
@RequiredArgsConstructor
public class LdapSearchController {

    private final LdapUserSearchService ldapSearch;

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam(name = "q", required = false) String q) {
        List<LdapUserSearchService.LdapUser> users = ldapSearch.search(q);
        return ResponseEntity.ok(Map.of(
                "available", ldapSearch.isAvailable(),
                "query", q == null ? "" : q,
                "count", users.size(),
                "users", users
        ));
    }

    /**
     * Endpoint debug — buka di browser saat login sebagai admin:
     *   GET /admin/ldap/debug?q=ad
     *
     * Return informasi yang lebih verbose (filter LDAP yang dipakai, base DN, error stack).
     * Berguna untuk diagnose kenapa hasil kosong.
     */
    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> debug(@RequestParam(name = "q", defaultValue = "a") String q) {
        return ResponseEntity.ok(ldapSearch.debugSearch(q));
    }
}
