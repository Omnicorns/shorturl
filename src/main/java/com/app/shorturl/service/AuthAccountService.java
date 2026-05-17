package com.app.shorturl.service;

import com.app.shorturl.model.AppUser;
import com.app.shorturl.model.PasswordResetToken;
import com.app.shorturl.repository.AppUserRepository;
import com.app.shorturl.repository.PasswordResetTokenRepository;
import com.app.shorturl.request.RegisterRequest;
import com.app.shorturl.request.ResetPasswordRequest;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class AuthAccountService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{3,40}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();


    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthAccountService(AppUserRepository userRepository,
                              PasswordResetTokenRepository resetTokenRepository,
                              PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser register(RegisterRequest request) {
        String fullName = safeTrim(request.getFullName());
        String username = safeTrim(request.getUsername());
        String email = safeTrim(request.getEmail()).toLowerCase();
        String password = request.getPassword() == null ? "" : request.getPassword();
        String confirmPassword = request.getConfirmPassword() == null ? "" : request.getConfirmPassword();

        if (fullName.length() < 2) {
            throw new IllegalArgumentException("Nama lengkap wajib diisi.");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("Username minimal 3 karakter, hanya huruf/angka/titik/underscore/dash.");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Format email tidak valid.");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("Password minimal 8 karakter.");
        }
        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Konfirmasi password tidak sama.");
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("Username sudah digunakan.");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email sudah digunakan.");
        }

        AppUser user = new AppUser();
        user.setFullName(fullName);
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("ROLE_ADMIN");
        user.setEnabled(true);
        return userRepository.save(user);
    }

    /**
     * Return Optional.empty() kalau user tidak ditemukan.
     * Controller tetap harus menampilkan pesan sukses generik agar tidak membocorkan akun terdaftar.
     */
    @Transactional
    public Optional<String> createPasswordResetToken(String identity) {
        return userRepository.findByUsernameOrEmail(safeTrim(identity))
                .map(user -> {
                    resetTokenRepository.deleteByUser(user);

                    String rawToken = generateRawToken();
                    PasswordResetToken resetToken = new PasswordResetToken();
                    resetToken.setUser(user);
                    resetToken.setTokenHash(sha256(rawToken));
                    resetToken.setExpiresAt(LocalDateTime.now().plusMinutes(30));
                    resetTokenRepository.save(resetToken);
                    return rawToken;
                });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String token = safeTrim(request.getToken());
        String password = request.getPassword() == null ? "" : request.getPassword();
        String confirmPassword = request.getConfirmPassword() == null ? "" : request.getConfirmPassword();

        if (token.isBlank()) {
            throw new IllegalArgumentException("Token reset tidak valid.");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("Password minimal 8 karakter.");
        }
        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Konfirmasi password tidak sama.");
        }

        PasswordResetToken resetToken = resetTokenRepository.findByTokenHashAndUsedAtIsNull(sha256(token))
                .orElseThrow(() -> new IllegalArgumentException("Token reset tidak valid atau sudah digunakan."));

        if (resetToken.isExpired()) {
            throw new IllegalArgumentException("Token reset sudah kedaluwarsa.");
        }

        AppUser user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(password));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        resetToken.setUsedAt(LocalDateTime.now());
        resetTokenRepository.save(resetToken);
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
