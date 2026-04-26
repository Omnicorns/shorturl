package com.app.shorturl.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class CatalogueController {
    @GetMapping("/catalogue")
    public String catalogue(
            @RequestParam(name = "id", required = false) Long id,
            @RequestParam(name = "fallback", required = false) String fallback, // opsional: jika tidak pakai DB
            Model model) {

        String pdfUrl;
        if (id != null) {
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
