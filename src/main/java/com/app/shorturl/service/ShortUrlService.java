package com.app.shorturl.service;

import com.app.shorturl.model.ShortUrl;
import com.app.shorturl.repository.ShortUrlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ShortUrlService {

    private static final String ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int DEFAULT_CODE_LENGTH = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShortUrlRepository repository;

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

        ShortUrl s = ShortUrl.builder()
                .shortCode(code)
                .originalUrl(normalized)
                .title(title != null && !title.isBlank() ? title.trim() : null)
                .clickCount(0L)
                .active(true)
                .createdAt(LocalDateTime.now())
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

    public Page<ShortUrl> list(String query, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return repository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return repository.search(query.trim(), pageable);
    }

    public Optional<ShortUrl> findByCode(String code) {
        return repository.findByShortCode(code);
    }

    @Transactional
    public void trackAccess(Long id) {
        repository.incrementClick(id, LocalDateTime.now());
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    @Transactional
    public ShortUrl toggleActive(Long id) {
        ShortUrl s = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Not found"));
        s.setActive(!Boolean.TRUE.equals(s.getActive()));
        return repository.save(s);
    }

    public long totalUrls() {
        return repository.count();
    }

    public long totalClicks() {
        Long total = repository.totalClicks();
        return total != null ? total : 0L;
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = randomCode(DEFAULT_CODE_LENGTH);
            if (!repository.existsByShortCode(code)) return code;
        }
        // fallback: panjangin kalau collision terus
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
}
