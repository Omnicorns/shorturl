package com.app.shorturl.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "short_urls", indexes = {
        @Index(name = "idx_short_code", columnList = "shortCode", unique = true),
        @Index(name = "idx_owner_username", columnList = "ownerUsername")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16)
    private String shortCode;

    @Column(nullable = false, length = 2048)
    private String originalUrl;

    @Column(length = 255)
    private String title;

    @Column(nullable = false)
    private Long clickCount = 0L;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime lastAccessedAt;

    @Column(nullable = false)
    private Boolean active = true;

    // ═════════════════════════════════════════════════════════════════
    //  AKSES KONTROL
    // ─────────────────────────────────────────────────────────────────
    //  ownerUsername : siapa yang create URL ini (login username, lowercase).
    //                  Hanya owner (+ co-managers + super-admin) yang bisa
    //                  melihat & kelola URL di dashboard.
    //  coManagers    : daftar username tambahan yang boleh kelola URL ini
    //                  (lowercase). Disimpan di tabel terpisah
    //                  `short_url_co_managers`.
    // ═════════════════════════════════════════════════════════════════
    @Column(length = 100)
    private String ownerUsername;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "short_url_co_managers",
            joinColumns = @JoinColumn(name = "short_url_id"),
            indexes = @Index(name = "idx_co_manager_username", columnList = "username")
    )
    @Column(name = "username", length = 100, nullable = false)
    @Builder.Default
    private Set<String> coManagers = new HashSet<>();

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (clickCount == null) clickCount = 0L;
        if (active == null) active = true;
        if (coManagers == null) coManagers = new HashSet<>();
    }

    /**
     * Helper: cek apakah user (case-insensitive) punya akses kelola URL ini.
     * Super-admin (cek di service) di-handle terpisah.
     */
    public boolean isAccessibleBy(String username) {
        if (username == null) return false;
        String u = username.toLowerCase();
        if (ownerUsername != null && ownerUsername.equalsIgnoreCase(u)) return true;
        if (coManagers != null) {
            for (String cm : coManagers) {
                if (cm != null && cm.equalsIgnoreCase(u)) return true;
            }
        }
        return false;
    }
}
