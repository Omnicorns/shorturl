package com.app.shorturl.controller;

import com.app.shorturl.request.RegisterRequest;
import com.app.shorturl.request.ResetPasswordRequest;
import com.app.shorturl.service.AuthAccountService;
import com.app.shorturl.service.CatalogAccessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthWebController {


    private final AuthAccountService authAccountService;
    private final CatalogAccessService catalogAccessService;



//    @GetMapping("/login")
//    public String loginPage() {
//        // Ganti ke nama file template kamu kalau berbeda.
//        // Contoh: src/main/resources/templates/login.html => return "login";
//        return "login_register_forgot_no_scroll";
//    }

    @PostMapping("/register")
    public String register(@ModelAttribute RegisterRequest request,
                           RedirectAttributes redirectAttributes) {
        try {
            authAccountService.register(request);
            redirectAttributes.addFlashAttribute("success", "Akun berhasil dibuat. Silakan login.");
            return "redirect:/login?registered";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            return "redirect:/login?registerError";
        }
    }

    @PostMapping("/forgot-password")
    public String forgotPassword(@RequestParam("identity") String identity,
                                 HttpServletRequest request,
                                 RedirectAttributes redirectAttributes) {

            authAccountService.createPasswordResetToken(identity).ifPresentOrElse(rawToken -> {
            String resetUrl = baseUrl(request) + "/reset-password?token=" + rawToken;

            // DEV MODE: tampilkan link di halaman login karena belum ada SMTP
            redirectAttributes.addFlashAttribute("resetUrl", resetUrl);
            redirectAttributes.addFlashAttribute("success", "Link reset password berhasil dibuat.");

            log.warn("PASSWORD RESET URL: {}", resetUrl);

        }, () -> {
            // Pesan tetap dibuat aman agar tidak membocorkan akun terdaftar/tidak
            redirectAttributes.addFlashAttribute("success", "Jika akun ditemukan, link reset password akan dibuat.");
        });

        return "redirect:/login?resetSent";
    }

    @GetMapping("/reset-password")
    public String resetPasswordPage(@RequestParam("token") String token, Model model) {
        model.addAttribute("token", token);
        return "reset_password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@ModelAttribute ResetPasswordRequest request,
                                RedirectAttributes redirectAttributes) {
        try {
            authAccountService.resetPassword(request);
            redirectAttributes.addFlashAttribute("success", "Password berhasil diganti. Silakan login.");
            return "redirect:/login?passwordChanged";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            return "redirect:/reset-password?token=" + request.getToken();
        }
    }

    @PostMapping("/admin/catalog/{id}/access")
    public String updateCatalogAccess(@PathVariable Long id,
                                      @RequestParam(value = "coManagers", required = false) String coManagers,
                                      @RequestParam(value = "newOwner", required = false) String newOwner,
                                      Authentication authentication,
                                      RedirectAttributes redirectAttributes) {
        try {
            catalogAccessService.updateAccess(id, coManagers, newOwner, authentication);
            redirectAttributes.addFlashAttribute("success", "Akses catalog berhasil diperbarui.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }

        return "redirect:/admin?tab=catalogs#catalogs";
    }

    private static String baseUrl(HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) scheme = request.getScheme();

        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) host = request.getServerName() + portPart(request);

        return scheme + "://" + host;
    }

    private static String portPart(HttpServletRequest request) {
        int port = request.getServerPort();
        if (("http".equals(request.getScheme()) && port == 80) ||
            ("https".equals(request.getScheme()) && port == 443)) {
            return "";
        }
        return ":" + port;
    }


}
