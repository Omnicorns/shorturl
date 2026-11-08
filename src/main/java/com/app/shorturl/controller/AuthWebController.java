package com.app.shorturl.controller;

import com.app.shorturl.config.AccessControlHelper;
import com.app.shorturl.request.RegisterRequest;
import com.app.shorturl.request.ResetPasswordRequest;
import com.app.shorturl.service.AuthAccountService;
import com.app.shorturl.service.CatalogAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final AccessControlHelper accessControl;

    @Value("${app.base-url:}")
    private String configuredBaseUrl;



//    @GetMapping("/login")
//    public String loginPage() {
//        // Ganti ke nama file template kamu kalau berbeda.
//        // Contoh: src/main/resources/templates/login.html => return "login";
//        return "login_register_forgot_no_scroll";
//    }

    /**
     * Membuat akun lokal baru (ROLE_ADMIN). Sebelumnya endpoint ini terbuka untuk
     * publik → siapa pun bisa membuat akun admin. Sekarang hanya super-admin.
     */
    @PostMapping("/register")
    public String register(@ModelAttribute RegisterRequest request,
                           RedirectAttributes redirectAttributes) {
        if (!accessControl.isCurrentUserSuperAdmin()) {
            redirectAttributes.addFlashAttribute("error", "Hanya super admin yang boleh membuat akun.");
            return "redirect:/admin";
        }
        try {
            authAccountService.register(request);
            redirectAttributes.addFlashAttribute("success", "Akun berhasil dibuat. Silakan login.");
            return "redirect:/admin?tab=users";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            return "redirect:/admin?tab=users";
        }
    }

    /**
     * Reset password dibantu admin (belum ada SMTP).
     *
     * Sebelumnya: siapa pun (tanpa login) bisa meminta link reset untuk akun mana pun
     * dan token-nya langsung ditampilkan di response + ditulis ke log → pengambilalihan akun.
     * Sekarang:
     *  - hanya super-admin yang bisa membuat link (link diserahkan ke user lewat kanal aman),
     *  - token TIDAK ditulis ke log,
     *  - host link diambil dari app.base-url (bukan header X-Forwarded-Host dari client).
     */
    @PostMapping("/forgot-password")
    public String forgotPassword(@RequestParam("identity") String identity,
                                 HttpServletRequest request,
                                 RedirectAttributes redirectAttributes) {
        if (!accessControl.isCurrentUserSuperAdmin()) {
            redirectAttributes.addFlashAttribute("error", "Hanya super admin yang boleh membuat link reset password.");
            return "redirect:/admin";
        }

        authAccountService.createPasswordResetToken(identity).ifPresentOrElse(rawToken -> {
            String resetUrl = baseUrl(request) + "/reset-password?token=" + rawToken;

            redirectAttributes.addFlashAttribute("resetUrl", resetUrl);
            redirectAttributes.addFlashAttribute("success",
                    "Link reset password dibuat (berlaku 30 menit, sekali pakai). Serahkan hanya ke pemilik akun.");

            log.info("Password reset link dibuat oleh {} untuk identity '{}'",
                    accessControl.currentUsername(), identity);

        }, () -> {
            // Pesan tetap dibuat aman agar tidak membocorkan akun terdaftar/tidak
            redirectAttributes.addFlashAttribute("success", "Jika akun ditemukan, link reset password akan dibuat.");
        });

        return "redirect:/admin?tab=users";
    }

    @GetMapping("/reset-password")
    public String resetPasswordPage(@RequestParam("token") String token,
                                    HttpServletResponse response,
                                    Model model) {
        // Jangan bocorkan token lewat header Referer ke resource/situs lain; jangan di-cache.
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
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

    private String baseUrl(HttpServletRequest request) {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return configuredBaseUrl.trim().replaceAll("/+$", "");
        }
        // Fallback: server.forward-headers-strategy=native sudah menerjemahkan header
        // proxy tepercaya ke getScheme()/getServerName(); jangan baca header mentah.
        return request.getScheme() + "://" + request.getServerName() + portPart(request);
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
