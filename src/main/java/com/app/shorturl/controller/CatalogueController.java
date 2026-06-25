package com.app.shorturl.controller;

import com.app.shorturl.repository.PdfDocRepository;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/catalogue")
    @Transactional
    public String catalogue(
            @RequestParam(name = "id", required = false) Long id,
            @RequestParam(name = "fallback", required = false) String fallback, // opsional: jika tidak pakai DB
            Model model) {

        String pdfUrl;
        if (id != null) {
            // Hitung akses: setiap kali viewer dibuka untuk catalog ini.
            // UPDATE atomik; jika id tidak ada, 0 baris terpengaruh (aman).
            pdfDocRepository.incrementAccess(id, LocalDateTime.now());
            pdfUrl = "/api/admin/users/pdf/" + id;
        } else if (fallback != null && !fallback.isBlank()) {
            // mis: /catalogue?fallback=/docs/file.pdf
            pdfUrl = fallback;
        } else {
            // default ke static
            pdfUrl = "/file.pdf";
        }

        model.addAttribute("pdfUrl", pdfUrl);
        return "flip"; // nama template viewer-mu
    }
}
