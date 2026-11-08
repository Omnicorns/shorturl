package com.app.shorturl.config;

import com.app.shorturl.model.PdfDocs;
import com.app.shorturl.model.ShortUrl;
import com.app.shorturl.repository.PdfDocRepository;
import com.app.shorturl.repository.ShortUrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrasi sekali jalan (idempotent) saat startup:
 * <ol>
 *   <li>Beri {@code access_key} ke semua catalog lama yang belum punya.</li>
 *   <li>Short URL yang mengarah ke {@code /catalogue?id=N} tanpa {@code k=} ditambah
 *       kunci akses, supaya link/QR yang sudah tersebar tetap berfungsi setelah
 *       akses PDF berbasis id saja ditutup.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfAccessKeyMigration implements ApplicationRunner {

    private static final Pattern CATALOGUE_ID =
            Pattern.compile("/catalogue\\?(?:[^#]*&)?id=(\\d+)");

    private final PdfDocRepository pdfDocRepository;
    private final ShortUrlRepository shortUrlRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer keys = transactionTemplate.execute(status -> backfillKeys());
            Integer urls = transactionTemplate.execute(status -> migrateShortUrls());
            if ((keys != null && keys > 0) || (urls != null && urls > 0)) {
                log.info("PDF access-key migration: {} catalog diberi kunci, {} short URL diperbarui",
                        keys, urls);
            }
        } catch (Exception e) {
            log.error("PDF access-key migration gagal: {}", e.getMessage(), e);
        }
    }

    private int backfillKeys() {
        int n = 0;
        for (Long id : pdfDocRepository.findIdsWithoutAccessKey()) {
            n += pdfDocRepository.initAccessKey(id, PdfDocs.newAccessKey());
        }
        return n;
    }

    private int migrateShortUrls() {
        int n = 0;
        List<ShortUrl> candidates = shortUrlRepository.findByOriginalUrlContaining("/catalogue?");
        for (ShortUrl s : candidates) {
            String url = s.getOriginalUrl();
            if (url == null || url.contains("k=")) continue;

            Matcher m = CATALOGUE_ID.matcher(url);
            if (!m.find()) continue;

            Long id = Long.valueOf(m.group(1));
            String key = pdfDocRepository.findAccessKeyById(id).orElse(null);
            if (key == null || key.isBlank()) continue;

            int hash = url.indexOf('#');
            String base = hash >= 0 ? url.substring(0, hash) : url;
            String frag = hash >= 0 ? url.substring(hash) : "";
            String updated = base + "&k=" + key + frag;
            if (updated.length() > 2048) continue;

            s.setOriginalUrl(updated);
            shortUrlRepository.save(s);
            n++;
        }
        return n;
    }
}
