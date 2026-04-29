package id.sarinah.link.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proxy + cache untuk feed reels Sarinah Indonesia dari RSS.app.
 *
 * - Cache di-refresh otomatis tiap 1 jam (lihat @Scheduled di bawah)
 * - Endpoint frontend: GET /api/sarinah-reels
 * - URL RSS.app disimpan di application.properties: sarinah.rssapp.feed-url
 */
@RestController
@RequestMapping("/api")
public class SarinahReelsController {

    @Value("${sarinah.rssapp.feed-url:}")
    private String rssAppFeedUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String cachedJson = null;
    private volatile long cachedAt = 0L;

    // Regex untuk ambil shortcode reel dari URL Instagram
    private static final Pattern REEL_PATTERN =
            Pattern.compile("instagram\\.com/(?:reel|p|tv)/([A-Za-z0-9_-]+)");

    @GetMapping(value = "/sarinah-reels", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getReels() {
        // Cache 1 jam
        long now = System.currentTimeMillis();
        if (cachedJson != null && (now - cachedAt) < 60 * 60 * 1000L) {
            return ResponseEntity.ok(cachedJson);
        }

        try {
            String fresh = fetchAndTransform();
            cachedJson = fresh;
            cachedAt = now;
            return ResponseEntity.ok(fresh);
        } catch (Exception ex) {
            // Kalau gagal & masih ada cache lama, pakai cache lama
            if (cachedJson != null) {
                return ResponseEntity.ok(cachedJson);
            }
            return ResponseEntity.ok("{\"items\":[],\"error\":\"unavailable\"}");
        }
    }

    /**
     * Refresh otomatis tiap 1 jam — supaya user pertama tidak menunggu.
     */
    @Scheduled(fixedRate = 60 * 60 * 1000L, initialDelay = 30 * 1000L)
    public void scheduledRefresh() {
        try {
            cachedJson = fetchAndTransform();
            cachedAt = System.currentTimeMillis();
        } catch (Exception ignored) { }
    }

    private String fetchAndTransform() throws Exception {
        if (rssAppFeedUrl == null || rssAppFeedUrl.isBlank()) {
            throw new IllegalStateException("sarinah.rssapp.feed-url belum diset");
        }

        String raw = restTemplate.getForObject(rssAppFeedUrl, String.class);
        JsonNode root = mapper.readTree(raw);
        JsonNode items = root.path("items");

        ArrayNode out = mapper.createArrayNode();
        int count = 0;

        for (JsonNode it : items) {
            if (count >= 3) break;

            String url = it.path("url").asText("");
            String title = it.path("title").asText("");
            String summary = it.path("summary").asText("");
            String image = it.path("image").asText("");
            if (image.isBlank()) {
                image = it.path("banner_image").asText("");
            }
            String datePublished = it.path("date_published").asText("");

            // Cuma ambil yang link-nya benar-benar reel/post Instagram
            Matcher m = REEL_PATTERN.matcher(url);
            if (!m.find()) continue;
            String shortcode = m.group(1);
            // Normalize ke /reel/ supaya konsisten
            String cleanUrl = "https://www.instagram.com/reel/" + shortcode + "/";

            // Bersihkan caption
            String caption = stripHtml(summary);
            if (caption.isBlank()) caption = stripHtml(title);
            if (caption.length() > 180) caption = caption.substring(0, 177) + "…";

            // Title pendek (3-7 kata pertama)
            String shortTitle = makeShortTitle(caption);

            ObjectNode node = mapper.createObjectNode();
            node.put("url", cleanUrl);
            node.put("shortcode", shortcode);
            node.put("title", shortTitle);
            node.put("caption", caption);
            node.put("image", image);
            node.put("publishedAt", datePublished);
            out.add(node);
            count++;
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("items", out);
        result.put("updatedAt", Instant.now().toString());
        return mapper.writeValueAsString(result);
    }

    private String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String makeShortTitle(String caption) {
        if (caption == null || caption.isBlank()) {
            return "Reels Sarinah Indonesia";
        }
        // Ambil kalimat pertama, batasi ~60 char
        String first = caption.split("[.!?]")[0].trim();
        if (first.length() > 70) first = first.substring(0, 67) + "…";
        return first;
    }
}



