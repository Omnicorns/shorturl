package com.app.shorturl.controller;

import com.app.shorturl.repository.PdfDocRepository;
import com.app.shorturl.service.CatalogAccessService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;

@Controller
@RequiredArgsConstructor
public class CatalogueController {

    private final PdfDocRepository pdfDocRepository;
    private final CatalogAccessService catalogAccessService;

    @Value("${app.pdf.allow-legacy-id-links:false}")
    private boolean allowLegacyIdLinks;

    @GetMapping("/catalogue")
    @Transactional
    public String catalogue(
            @RequestParam(name = "id", required = false) Long id,
            @RequestParam(name = "k", required = false) String accessKey,
            @RequestParam(name = "fallback", required = false) String fallback, // opsional: jika tidak pakai DB
            Authentication authentication,
            HttpServletResponse response,
            Model model) {

        String pdfUrl;
        if (id != null) {
            boolean allowed = catalogAccessService.hasValidAccessKey(id, accessKey)
                    || catalogAccessService.canView(id, authentication)
                    || (allowLegacyIdLinks && pdfDocRepository.existsById(id));
            if (!allowed) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                model.addAttribute("code", "catalogue");
                return "not-found";
            }

            // Hitung akses: setiap kali viewer dibuka untuk catalog ini.
            // UPDATE atomik; jika id tidak ada, 0 baris terpengaruh (aman).
            pdfDocRepository.incrementAccess(id, LocalDateTime.now());
            String key = pdfDocRepository.findAccessKeyById(id).orElse(null);
            pdfUrl = CatalogAccessService.streamUrl(id, key);
        } else if (isSafeLocalPath(fallback)) {
            // mis: /catalogue?fallback=/docs/file.pdf  (hanya path lokal, bukan URL luar)
            pdfUrl = fallback;
        } else {
            // default ke static
            pdfUrl = "/file.pdf";
        }

        response.setHeader("Referrer-Policy", "no-referrer");
        model.addAttribute("pdfUrl", pdfUrl);
        return "flip"; // nama template viewer-mu
    }

    private static boolean isSafeLocalPath(String p) {
        return p != null && !p.isBlank()
                && p.startsWith("/")
                && !p.startsWith("//")
                && !p.contains("\\")
                && !p.contains(":");
    }
}
