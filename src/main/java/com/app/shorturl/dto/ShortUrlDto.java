package com.app.shorturl.dto;

import com.app.shorturl.model.ShortUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class ShortUrlDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Request untuk membuat short URL baru")
    public static class CreateRequest {
        @NotBlank(message = "URL asli tidak boleh kosong")
        @Size(max = 2048, message = "URL terlalu panjang (max 2048)")
        @Schema(description = "URL tujuan yang ingin dipersingkat",
                example = "https://id.wikipedia.org/wiki/Batik", requiredMode = Schema.RequiredMode.REQUIRED)
        private String originalUrl;

        @Schema(description = "Judul untuk short URL (opsional)",
                example = "Artikel Batik Indonesia")
        private String title;

        @Pattern(regexp = "^[a-zA-Z0-9_-]{3,16}$|^$",
                message = "Custom code harus 3-16 karakter (huruf, angka, _ atau -)")
        @Schema(description = "Kode custom 3-16 karakter (opsional, auto-generate jika kosong)",
                example = "batik-nusantara")
        private String customCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Response data short URL")
    public static class Response {
        @Schema(example = "1")
        private Long id;

        @Schema(example = "batik-nusantara")
        private String shortCode;

        @Schema(example = "http://localhost:8080/batik-nusantara")
        private String shortUrl;

        @Schema(example = "https://id.wikipedia.org/wiki/Batik")
        private String originalUrl;

        @Schema(example = "Artikel Batik Indonesia")
        private String title;

        @Schema(example = "42")
        private Long clickCount;

        @Schema(example = "true")
        private Boolean active;

        private LocalDateTime createdAt;

        private LocalDateTime lastAccessedAt;

        public static Response from(ShortUrl s, String baseUrl) {
            return Response.builder()
                    .id(s.getId())
                    .shortCode(s.getShortCode())
                    .shortUrl(baseUrl + "/" + s.getShortCode())
                    .originalUrl(s.getOriginalUrl())
                    .title(s.getTitle())
                    .clickCount(s.getClickCount())
                    .active(s.getActive())
                    .createdAt(s.getCreatedAt())
                    .lastAccessedAt(s.getLastAccessedAt())
                    .build();
        }
    }

    @Data
    @AllArgsConstructor
    @Schema(description = "Response error standar")
    public static class ErrorResponse {
        @Schema(example = "400")
        private int status;

        @Schema(example = "URL tidak boleh kosong")
        private String message;

        @Schema(example = "2026-04-20T10:30:00")
        private LocalDateTime timestamp;

        public ErrorResponse(int status, String message) {
            this.status = status;
            this.message = message;
            this.timestamp = LocalDateTime.now();
        }
    }
}
