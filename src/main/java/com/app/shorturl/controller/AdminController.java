package com.app.shorturl.controller;

import com.app.shorturl.model.ShortUrl;
import com.app.shorturl.repository.ClickLogRepository;
import com.app.shorturl.service.ShortUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ShortUrlService service;
    private final ClickLogRepository clickLogRepository;

    @Value("${app.base-url}")
    private String baseUrl;

    @GetMapping
    public String dashboard(@RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "10") int size,
                            Model model) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<ShortUrl> urls = service.list(q, pageable);

        model.addAttribute("urls", urls);
        model.addAttribute("q", q);
        model.addAttribute("totalUrls", service.totalUrls());
        model.addAttribute("totalClicks", service.totalClicks());
        model.addAttribute("baseUrl", baseUrl);
        return "admin/dashboard";
    }

    @PostMapping("/create")
    public String create(@RequestParam String originalUrl,
                         @RequestParam(required = false) String title,
                         @RequestParam(required = false) String customCode,
                         RedirectAttributes ra) {
        try {
            ShortUrl created = service.create(originalUrl, title, customCode);
            ra.addFlashAttribute("success",
                "Short URL dibuat: " + baseUrl + "/" + created.getShortCode());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.delete(id);
            ra.addFlashAttribute("success", "Short URL dihapus");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Gagal menghapus: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        try {
            ShortUrl s = service.toggleActive(id);
            ra.addFlashAttribute("success",
                "Status diubah: " + (s.getActive() ? "Aktif" : "Nonaktif"));
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Gagal: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    /**
     * Heartbeat ringan untuk auto-refresh polling dari frontend.
     * Session-based auth (ikut login admin), return JSON.
     */
    @GetMapping("/heartbeat")
    @ResponseBody
    public Map<String, Long> heartbeat() {
        return Map.of(
                "totalUrls", service.totalUrls(),
                "totalClicks", service.totalClicks(),
                "totalLogs", clickLogRepository.count()
        );
    }
}
