package com.app.shorturl.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-fetch thumbnail dari permalink Instagram.
 *
 * SSL OPTION:
 *   app.ig.skip-ssl-verification=true  → skip verifikasi cert (dev local)
 *   app.ig.skip-ssl-verification=false → normal (production, default)
 */
@Slf4j
@Service
public class IgThumbnailFetcherService {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Pattern SHORTCODE_PATTERN =
            Pattern.compile("instagram\\.com/(?:p|reel|tv)/([A-Za-z0-9_-]+)");

    private static final Pattern OG_IMAGE_PATTERN = Pattern.compile(
            "<meta\\s+property=\"og:image\"\\s+content=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DISPLAY_URL_PATTERN = Pattern.compile(
            "\"display_url\"\\s*:\\s*\"([^\"]+)\""
    );

    @Value("${app.ig.upload-dir:./uploads/ig}")
    private String uploadDir;

    @Value("${app.ig.upload-url-prefix:/uploads/ig}")
    private String urlPrefix;

    /**
     * Skip verifikasi SSL — HANYA untuk dev local kalau kena PKIX error.
     * JANGAN aktifkan di production.
     */
    @Value("${app.ig.skip-ssl-verification:false}")
    private boolean skipSslVerification;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        // Buat folder upload
        try {
            Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            log.info("IG thumbnail directory ready: {}", dir);
        } catch (IOException e) {
            log.error("Gagal buat folder upload: {}", uploadDir, e);
        }

        // Buat HttpClient
        if (skipSslVerification) {
            log.warn("⚠️  SSL verification DISABLED for IG thumbnail fetcher. " +
                    "Ini hanya boleh untuk dev local!");
            httpClient = createInsecureClient();
        } else {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
    }

    /**
     * HttpClient yang trust SEMUA certificate.
     * Pakai cuma kalau JVM trust store di local kamu rusak / outdated /
     * kena MITM proxy kantor.
     */
    private HttpClient createInsecureClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());

            // Disable hostname verification juga
            SSLParameters sslParams = new SSLParameters();
            sslParams.setEndpointIdentificationAlgorithm(null);

            // System property untuk JDK HttpClient (kalau ada)
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");

            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(sslContext)
                    .sslParameters(sslParams)
                    .build();
        } catch (Exception e) {
            log.error("Gagal buat insecure HttpClient, fallback ke default", e);
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
    }

    public String extractShortcode(String permalink) {
        if (permalink == null) return null;
        Matcher m = SHORTCODE_PATTERN.matcher(permalink);
        return m.find() ? m.group(1) : null;
    }

    public String fetchAndSave(String permalink) {
        String shortcode = extractShortcode(permalink);
        if (shortcode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Permalink tidak valid: tidak bisa extract shortcode dari " + permalink);
        }

        String embedUrl = "https://www.instagram.com/p/" + shortcode + "/embed/captioned/";
        String html = fetchHtml(embedUrl);
        if (html == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Tidak bisa akses Instagram. Coba upload thumbnail manual atau retry nanti.");
        }

        String imageUrl = extractImageUrl(html);
        if (imageUrl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Thumbnail tidak ditemukan di Instagram embed. Post mungkin private atau dihapus.");
        }

        return downloadAndSave(imageUrl, shortcode);
    }

    private String fetchHtml(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
            log.warn("IG embed returned status {}: {}", resp.statusCode(), url);
            return null;
        } catch (javax.net.ssl.SSLHandshakeException e) {
            log.error("SSL error fetching {}: {} — coba set app.ig.skip-ssl-verification=true di application.properties (HANYA untuk local!)",
                    url, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Gagal fetch IG embed {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String extractImageUrl(String html) {
        Matcher og = OG_IMAGE_PATTERN.matcher(html);
        if (og.find()) {
            return unescapeHtml(og.group(1));
        }
        Matcher du = DISPLAY_URL_PATTERN.matcher(html);
        if (du.find()) {
            return unescapeJson(du.group(1));
        }
        return null;
    }

    private String unescapeHtml(String s) {
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private String unescapeJson(String s) {
        return s.replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\\"", "\"");
    }

    private String downloadAndSave(String imageUrl, String shortcode) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.instagram.com/")
                    .GET()
                    .build();

            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200 || resp.body().length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Gagal download gambar dari Instagram (status " + resp.statusCode() + ")");
            }

            String contentType = resp.headers().firstValue("content-type").orElse("image/jpeg");
            String ext = contentType.contains("png") ? "png"
                    : contentType.contains("webp") ? "webp"
                    : "jpg";

            String fileName = "ig-" + shortcode + "-" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
            Path target = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(fileName);

            Files.write(target, resp.body());
            log.info("Saved IG thumbnail for {}: {} ({} bytes)", shortcode, fileName, resp.body().length);

            String prefix = urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/";
            return prefix + fileName;

        } catch (ResponseStatusException e) {
            throw e;
        } catch (javax.net.ssl.SSLHandshakeException e) {
            log.error("SSL error download {}: {}", imageUrl, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "SSL error saat download. Set app.ig.skip-ssl-verification=true (untuk local).");
        } catch (Exception e) {
            log.error("Gagal download/simpan thumbnail {}", imageUrl, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Gagal simpan gambar ke disk: " + e.getMessage());
        }
    }
}