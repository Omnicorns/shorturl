package com.app.shorturl.controller;

import com.app.shorturl.config.AccessControlHelper;
import com.app.shorturl.model.ShortUrl;
import com.app.shorturl.repository.ClickLogRepository;
import com.app.shorturl.service.ShortUrlService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
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
    private final AccessControlHelper accessControl;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * Pastikan attribute akses-kontrol SELALU tersedia di setiap view yang
     * di-render dari controller ini, sehingga template tidak pernah dapat
     * null saat mengevaluasi ekspresi `isSuperAdmin or ...`.
     */
    @ModelAttribute("currentUser")
    public String currentUserAttr() {
        return accessControl.currentUsername();
    }

    @ModelAttribute("isSuperAdmin")
    public boolean isSuperAdminAttr() {
        return accessControl.isCurrentUserSuperAdmin();
    }

    @GetMapping
    public String dashboard(@RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "10") int size,
                            HttpServletRequest request,
                            Model model) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<ShortUrl> urls = service.list(q, pageable);

        String resolvedBaseUrl = resolveBaseUrl(request);

        model.addAttribute("urls", urls);
        model.addAttribute("q", q);
        model.addAttribute("totalUrls", service.totalUrls());
        model.addAttribute("totalClicks", service.totalClicks());
        model.addAttribute("baseUrl", resolvedBaseUrl);

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
        } catch (AccessDeniedException e) {
            ra.addFlashAttribute("error", "Akses ditolak: " + e.getMessage());
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
        } catch (AccessDeniedException e) {
            ra.addFlashAttribute("error", "Akses ditolak: " + e.getMessage());
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Gagal: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    /**
     * Heartbeat ringan untuk auto-refresh polling dari frontend.
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

    @PostMapping("/{id}/toggle-ads")
    public String toggleAds(@PathVariable Long id, RedirectAttributes ra) {
        try {
            ShortUrl s = service.toggleNoAds(id);
            ra.addFlashAttribute("success",
                    Boolean.TRUE.equals(s.getNoAds())
                            ? "Iklan dimatikan untuk " + s.getShortCode()
                            : "Iklan diaktifkan untuk " + s.getShortCode());
        } catch (AccessDeniedException e) {
            ra.addFlashAttribute("error", "Akses ditolak: " + e.getMessage());
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Gagal: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.replaceAll("/+$", "");
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (!(("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443))) {
            sb.append(":").append(port);
        }
        return sb.toString();
    }

    @PostMapping("/{id}/update")
    public String updateShortUrl(@PathVariable Long id,
                                 @RequestParam String originalUrl,
                                 @RequestParam(required = false) String title,
                                 @RequestParam(required = false) String customCode,
                                 RedirectAttributes ra) {
        try {
            service.updateShortUrl(
                    id,
                    originalUrl,
                    blankToNull(title),
                    blankToNull(customCode)
            );
            ra.addFlashAttribute("success", "Short URL berhasil diperbarui.");
        } catch (AccessDeniedException e) {
            ra.addFlashAttribute("error", "Akses ditolak: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Gagal memperbarui: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    // ═══════════════════════════════════════════════════════════════════
    //  KELOLA AKSES — set co-managers
    // ═══════════════════════════════════════════════════════════════════
    @PostMapping("/{id}/access")
    public String updateAccess(@PathVariable Long id,
                               @RequestParam(required = false) String coManagers,
                               @RequestParam(required = false) String newOwner,
                               RedirectAttributes ra) {
        try {
            // Optional: transfer ownership dulu, baru set co-manager
            if (newOwner != null && !newOwner.isBlank()) {
                service.transferOwner(id, newOwner.trim());
            }
            service.setCoManagers(id, coManagers);
            ra.addFlashAttribute("success", "Akses berhasil diperbarui.");
        } catch (AccessDeniedException e) {
            ra.addFlashAttribute("error", "Akses ditolak: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Gagal memperbarui akses: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    private String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
