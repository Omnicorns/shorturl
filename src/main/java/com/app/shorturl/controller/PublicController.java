package com.app.shorturl.controller;

import com.app.shorturl.model.ShortUrl;
import com.app.shorturl.service.ClickLogService;
import com.app.shorturl.service.ShortUrlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;





@Controller
@RequiredArgsConstructor
public class PublicController {

    private final ShortUrlService service;
    private final ClickLogService clickLogService;

    @Value("${app.base-url}")
    private String baseUrl;

    private static final List<Map<String, String>> CULTURE_ITEMS = List.of(
            Map.of("tag", "UNESCO 2009",
                    "title", "Batik",
                    "description", "Teknik pewarnaan kain dengan malam (lilin panas) yang diakui UNESCO sebagai Warisan Budaya Takbenda Dunia. Motifnya kaya filosofi — dari Parang, Kawung, hingga Mega Mendung."),
            Map.of("tag", "Aceh",
                    "title", "Tari Saman",
                    "description", "Tarian tradisional Gayo dengan gerakan cepat, serempak, dan penuh harmoni. Terdaftar dalam Warisan Budaya Takbenda UNESCO sejak 2011."),
            Map.of("tag", "Sumatera Barat",
                    "title", "Rendang",
                    "description", "Masakan daging berbumbu rempah khas Minangkabau, dimasak perlahan dengan santan. Pernah dinobatkan sebagai makanan terenak di dunia oleh CNN."),
            Map.of("tag", "Bali",
                    "title", "Tari Kecak",
                    "description", "Pertunjukan sakral yang diiringi paduan suara 'cak' dari puluhan penari pria, menggambarkan kisah epos Ramayana."),
            Map.of("tag", "Sumatera Barat",
                    "title", "Rumah Gadang",
                    "description", "Rumah adat Minangkabau dengan atap bergonjong menyerupai tanduk kerbau — simbol kemenangan dan kebesaran adat."),
            Map.of("tag", "Sulawesi Selatan",
                    "title", "Tongkonan",
                    "description", "Rumah adat Toraja dengan atap melengkung menyerupai perahu, melambangkan asal-usul leluhur dari lautan."),
            Map.of("tag", "Papua",
                    "title", "Honai",
                    "description", "Rumah adat suku Dani berbentuk bulat dengan atap jerami kerucut, dirancang menahan dinginnya pegunungan tengah Papua."),
            Map.of("tag", "Nusa Tenggara",
                    "title", "Tenun Ikat",
                    "description", "Kain tenun dengan pola yang dibuat melalui teknik mengikat benang sebelum dicelup ke dalam pewarna alami dari tumbuhan."),
            Map.of("tag", "Jawa Tengah",
                    "title", "Wayang Kulit",
                    "description", "Seni pertunjukan boneka kulit yang diakui UNESCO pada 2003, mengangkat kisah Mahabharata dan Ramayana dengan dalang sebagai narator tunggal.")
    );

    @GetMapping("/")
    public String landing(Model model) {
        model.addAttribute("totalUrls", service.totalUrls());
        model.addAttribute("totalClicks", service.totalClicks());
        model.addAttribute("baseUrl", baseUrl);
        return "landing/index";
    }

    @PostMapping("/shorten")
    public String shortenPublic(@RequestParam String originalUrl,
                                @RequestParam(required = false) String customCode,
                                RedirectAttributes ra) {
        try {
            ShortUrl created = service.create(originalUrl, null, customCode);
            ra.addFlashAttribute("shortUrl", baseUrl + "/" + created.getShortCode());
            ra.addFlashAttribute("originalUrl", created.getOriginalUrl());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            ra.addFlashAttribute("lastInput", originalUrl);
        }
        return "redirect:/#shortener";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /**
     * Halaman preview short URL.
     *
     * Click logging dipindah ke sini (dari /go) dengan 2 alasan:
     *  1. Setiap user yang akses link akan lewat /preview dulu, jadi tidak ada akses
     *     yang lolos tracking.
     *  2. Kita butuh ID ClickLog yang baru dibuat, supaya browser user bisa
     *     update IP & geolocation-nya secara async (workaround untuk NAT provider
     *     yang menyembunyikan IP publik user).
     */
    @GetMapping("/{code:[a-zA-Z0-9_-]{3,16}}")
    public String previewShort(@PathVariable String code,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               Model model) {
        Optional<ShortUrl> opt = service.findByCode(code);
        if (opt.isEmpty() || Boolean.FALSE.equals(opt.get().getActive())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("code", code);
            return "not-found";
        }
        ShortUrl s = opt.get();

        // Increment click count (sync, supaya angka di UI selalu update)
        service.trackAccess(s.getId());

        // Log click — SYNC supaya dapat ID-nya, dipakai untuk IP tracking dari browser
        Long trackId = clickLogService.logClickAndReturnId(
                s.getId(),
                s.getShortCode(),
                ClickLogService.snapshot(request)
        );

        model.addAttribute("shortCode", s.getShortCode());
        model.addAttribute("originalUrl", s.getOriginalUrl());
        model.addAttribute("title", s.getTitle());
        model.addAttribute("clickCount", s.getClickCount());
        model.addAttribute("trackId", trackId);   // <-- dipakai oleh JS di preview.html
        model.addAttribute("culture",
                CULTURE_ITEMS.get(ThreadLocalRandom.current().nextInt(CULTURE_ITEMS.size())));
        return "preview";
    }

    /**
     * Endpoint /go — hanya untuk redirect.
     *
     * TIDAK lagi melakukan log click di sini (sudah di-log saat /preview),
     * supaya satu akses tidak tercatat dua kali.
     */
    @GetMapping("/{code:[a-zA-Z0-9_-]{3,16}}/go")
    public Object redirectDirect(@PathVariable String code,
                                 HttpServletResponse response,
                                 Model model) {
        Optional<ShortUrl> opt = service.findByCode(code);
        if (opt.isEmpty() || Boolean.FALSE.equals(opt.get().getActive())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("code", code);
            return "not-found";
        }
        ShortUrl s = opt.get();

        RedirectView rv = new RedirectView(s.getOriginalUrl());
        rv.setStatusCode(org.springframework.http.HttpStatus.FOUND);
        return rv;
    }
}