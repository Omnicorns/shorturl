package com.app.shorturl.service;

import com.app.shorturl.config.AccessControlHelper;
import com.app.shorturl.model.ShortUrl;
import com.app.shorturl.repository.ShortUrlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ShortUrlService {

    private static final String ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int DEFAULT_CODE_LENGTH = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShortUrlRepository repository;
    private final AccessControlHelper accessControl;

    private static final Pattern URL_PATTERN  = Pattern.compile("^https?://.+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{3,16}$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._@\\-]{1,100}$");

    // ═══════════════════════════════════════════════════════════════════
    //  CREATE — owner di-set otomatis dari user yang login
    // ═══════════════════════════════════════════════════════════════════
    @Transactional
    public ShortUrl create(String originalUrl, String title, String customCode) {
        String normalized = normalizeUrl(originalUrl);

        String code;
        if (customCode != null && !customCode.isBlank()) {
            if (!customCode.matches("^[a-zA-Z0-9_-]{3,16}$")) {
                throw new IllegalArgumentException(
                        "Custom code harus 3-16 karakter (huruf, angka, _ atau -)");
            }
            if (repository.existsByShortCode(customCode)) {
                throw new IllegalArgumentException("Custom code sudah digunakan");
            }
            code = customCode;
        } else {
            code = generateUniqueCode();
        }

        // Owner = user yang sedang login (atau null kalau dipanggil dari endpoint publik
        // tanpa auth — di sini akan jadi URL "yatim" yang hanya kelihatan super-admin)
        String owner = accessControl.currentUsername();

        ShortUrl s = ShortUrl.builder()
                .shortCode(code)
                .originalUrl(normalized)
                .title(title != null && !title.isBlank() ? title.trim() : null)
                .clickCount(0L)
                .active(true)
                .createdAt(LocalDateTime.now())
                .ownerUsername(owner)
                .coManagers(new HashSet<>())
                .build();
        return repository.save(s);
    }

    @Transactional
    public Optional<String> resolveAndTrack(String shortCode) {
        Optional<ShortUrl> opt = repository.findByShortCode(shortCode);
        if (opt.isEmpty() || Boolean.FALSE.equals(opt.get().getActive())) {
            return Optional.empty();
        }
        repository.incrementClick(opt.get().getId(), LocalDateTime.now());
        return Optional.of(opt.get().getOriginalUrl());
    }

    @Transactional
    public ShortUrl toggleNoAds(Long id) {
        ShortUrl s = loadAndAuthorize(id);
        s.setNoAds(!Boolean.TRUE.equals(s.getNoAds()));
        return repository.save(s);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LIST — owner-aware
    // ═══════════════════════════════════════════════════════════════════
    /**
     * List URL yang accessible oleh user yang sedang login.
     * - Super-admin → semua URL
     * - User biasa  → hanya URL yang dia owner atau co-manager
     */
    public Page<ShortUrl> list(String query, Pageable pageable) {
        if (accessControl.isCurrentUserSuperAdmin()) {
            // Super-admin: behavior lama, lihat semua
            if (query == null || query.isBlank()) {
                return repository.findAllByOrderByCreatedAtDesc(pageable);
            }
            return repository.search(query.trim(), pageable);
        }

        String user = accessControl.currentUsername();
        if (user == null) {
            // anonymous → tidak boleh list
            return Page.empty(pageable);
        }
        if (query == null || query.isBlank()) {
            return repository.findAccessibleByUser(user, pageable);
        }
        return repository.searchAccessibleByUser(query.trim(), user, pageable);
    }

    public Optional<ShortUrl> findByCode(String code) {
        return repository.findByShortCode(code);
    }

    /** Cek apakah current user boleh kelola URL ini. Lempar AccessDeniedException kalau tidak. */
    private ShortUrl loadAndAuthorize(Long id) {
        ShortUrl s = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Short URL dengan ID " + id + " tidak ditemukan."));
        if (!accessControl.isCurrentUserSuperAdmin()) {
            String user = accessControl.currentUsername();
            if (user == null || !s.isAccessibleBy(user)) {
                throw new AccessDeniedException(
                        "Anda tidak punya akses ke short URL ini.");
            }
        }
        return s;
    }

    @Transactional
    public void trackAccess(Long id) {
        repository.incrementClick(id, LocalDateTime.now());
    }

    @Transactional
    public void delete(Long id) {
        loadAndAuthorize(id);
        repository.deleteById(id);
    }

    @Transactional
    public ShortUrl toggleActive(Long id) {
        ShortUrl s = loadAndAuthorize(id);
        s.setActive(!Boolean.TRUE.equals(s.getActive()));
        return repository.save(s);
    }

    public long totalUrls() {
        if (accessControl.isCurrentUserSuperAdmin()) return repository.count();
        String user = accessControl.currentUsername();
        // Anonymous (landing page publik) → tampilkan total global
        if (user == null) return repository.count();
        return repository.countAccessibleByUser(user);
    }

    public long totalClicks() {
        if (accessControl.isCurrentUserSuperAdmin()) {
            Long total = repository.totalClicks();
            return total != null ? total : 0L;
        }
        String user = accessControl.currentUsername();
        // Anonymous (landing page publik) → tampilkan total global
        if (user == null) {
            Long total = repository.totalClicks();
            return total != null ? total : 0L;
        }
        Long total = repository.totalClicksByUser(user);
        return total != null ? total : 0L;
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = randomCode(DEFAULT_CODE_LENGTH);
            if (!repository.existsByShortCode(code)) return code;
        }
        return randomCode(DEFAULT_CODE_LENGTH + 2);
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private String normalizeUrl(String url) {
        if (url == null) throw new IllegalArgumentException("URL tidak boleh kosong");
        String u = url.trim();
        if (u.isEmpty()) throw new IllegalArgumentException("URL tidak boleh kosong");
        if (!u.matches("^https?://.*")) {
            u = "https://" + u;
        }
        if (u.length() > 2048) {
            throw new IllegalArgumentException("URL terlalu panjang (max 2048)");
        }
        return u;
    }

    @Transactional
    public ShortUrl updateShortUrl(Long id,
                                   String originalUrl,
                                   String title,
                                   String customCode) {

        ShortUrl url = loadAndAuthorize(id);

        // ─── Validasi & set originalUrl ───────────────────────────
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new IllegalArgumentException("URL Asli tidak boleh kosong.");
        }
        String urlTrimmed = originalUrl.trim();
        if (urlTrimmed.length() > 2048) {
            throw new IllegalArgumentException("URL terlalu panjang (maks 2048 karakter).");
        }
        if (!URL_PATTERN.matcher(urlTrimmed).matches()) {
            throw new IllegalArgumentException("URL harus diawali http:// atau https://");
        }
        url.setOriginalUrl(urlTrimmed);

        // ─── Set title ────────────────────────────────────────────
        if (title != null && title.length() > 255) {
            throw new IllegalArgumentException("Judul terlalu panjang (maks 255 karakter).");
        }
        url.setTitle(title);

        // ─── Validasi & set customCode (= shortCode) ─────────────
        if (customCode != null && !customCode.isBlank()) {
            String code = customCode.trim();

            if (!CODE_PATTERN.matcher(code).matches()) {
                throw new IllegalArgumentException(
                        "Custom Code harus 3-16 karakter dan hanya boleh huruf, angka, - dan _");
            }

            if (!code.equals(url.getShortCode())) {
                final Long currentId = id;
                repository.findByShortCode(code)
                        .filter(other -> !other.getId().equals(currentId))
                        .ifPresent(other -> {
                            throw new IllegalArgumentException(
                                    "Code '" + code + "' sudah dipakai short URL lain.");
                        });
                url.setShortCode(code);
            }
        }

        return repository.save(url);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  AKSES KONTROL — co-managers & transfer owner
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Set seluruh daftar co-manager untuk URL tertentu (replace).
     * Hanya owner atau super-admin yang boleh mengubah ini.
     *
     * @param coManagersCsv CSV username, contoh: "budi,siti,ahmad@sarinah.net"
     */
    @Transactional
    public ShortUrl setCoManagers(Long id, String coManagersCsv) {
        ShortUrl url = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Short URL dengan ID " + id + " tidak ditemukan."));

        // Hanya owner atau super-admin yang boleh ubah akses.
        // Co-manager TIDAK boleh menambah co-manager lain (mencegah eskalasi)
        String me = accessControl.currentUsername();
        boolean isSuper = accessControl.isCurrentUserSuperAdmin();
        boolean isOwner = me != null && url.getOwnerUsername() != null
                && me.equalsIgnoreCase(url.getOwnerUsername());
        if (!isSuper && !isOwner) {
            throw new AccessDeniedException(
                    "Hanya pemilik URL yang boleh mengelola akses.");
        }

        Set<String> next = parseUsernames(coManagersCsv);

        // Jangan masukkan owner ke list co-manager (redundant)
        if (url.getOwnerUsername() != null) {
            next.remove(url.getOwnerUsername().toLowerCase());
        }

        // Validasi format setiap username
        for (String u : next) {
            if (!USERNAME_PATTERN.matcher(u).matches()) {
                throw new IllegalArgumentException(
                        "Username tidak valid: '" + u + "'. " +
                                "Hanya huruf, angka, titik, underscore, @ dan strip.");
            }
        }

        url.setCoManagers(next);
        return repository.save(url);
    }

    /**
     * Transfer kepemilikan ke user lain.
     * Hanya owner saat ini atau super-admin yang boleh.
     */
    @Transactional
    public ShortUrl transferOwner(Long id, String newOwner) {
        if (newOwner == null || newOwner.isBlank()) {
            throw new IllegalArgumentException("Owner baru tidak boleh kosong.");
        }
        String owner = newOwner.trim().toLowerCase();
        if (!USERNAME_PATTERN.matcher(owner).matches()) {
            throw new IllegalArgumentException(
                    "Format username owner tidak valid.");
        }

        ShortUrl url = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Short URL dengan ID " + id + " tidak ditemukan."));

        String me = accessControl.currentUsername();
        boolean isSuper = accessControl.isCurrentUserSuperAdmin();
        boolean isOwner = me != null && url.getOwnerUsername() != null
                && me.equalsIgnoreCase(url.getOwnerUsername());
        if (!isSuper && !isOwner) {
            throw new AccessDeniedException(
                    "Hanya pemilik URL yang boleh mentransfer kepemilikan.");
        }

        url.setOwnerUsername(owner);
        // Owner baru pasti tidak perlu jadi co-manager juga
        if (url.getCoManagers() != null) {
            url.getCoManagers().remove(owner);
        }
        return repository.save(url);
    }

    /** Parse CSV → Set username lowercase, unik, trimmed. */
    private Set<String> parseUsernames(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) return out;
        // Pisah dengan koma, titik koma, newline, atau whitespace
        Arrays.stream(csv.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .forEach(out::add);
        return out;
    }
}
