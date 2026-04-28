package com.app.shorturl.dto;

import com.app.shorturl.model.IgPost;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

public class IgPostDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Request untuk create / update post Instagram")
    public static class CreateRequest {

        @NotBlank(message = "Permalink Instagram wajib diisi")
        @Pattern(
                regexp = "^https?://(www\\.)?instagram\\.com/(p|reel|tv)/[A-Za-z0-9_-]+/?.*$",
                message = "Format permalink tidak valid. Contoh: https://www.instagram.com/p/DXN9TnLkaGW/"
        )
        @Schema(description = "URL post Instagram",
                example = "https://www.instagram.com/p/DXN9TnLkaGW/",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String permalink;

        @Size(max = 200)
        @Schema(description = "Caption singkat untuk overlay tile (opsional)",
                example = "Promo Lebaran 2026")
        private String caption;

        @Size(max = 500)
        @Schema(description = "URL thumbnail (opsional, kosong = pakai gradient placeholder)",
                example = "/uploads/ig/post-1.jpg")
        private String mediaUrl;

        @Schema(description = "Urutan tampilan (lebih kecil = lebih atas)",
                example = "1", defaultValue = "0")
        private Integer displayOrder;

        @Schema(description = "Tampilkan di feed publik atau tidak",
                example = "true", defaultValue = "true")
        private Boolean isActive;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Detail post Instagram (untuk admin)")
    public static class Response {

        @Schema(example = "1")
        private Long id;

        @Schema(example = "https://www.instagram.com/p/DXN9TnLkaGW/")
        private String permalink;

        @Schema(example = "Promo Lebaran 2026")
        private String caption;

        @Schema(example = "/uploads/ig/post-1.jpg")
        private String mediaUrl;

        @Schema(example = "1")
        private Integer displayOrder;

        @Schema(example = "true")
        private Boolean isActive;

        private Instant createdAt;
        private Instant updatedAt;

        public static Response from(IgPost post) {
            return Response.builder()
                    .id(post.getId())
                    .permalink(post.getPermalink())
                    .caption(post.getCaption())
                    .mediaUrl(post.getMediaUrl())
                    .displayOrder(post.getDisplayOrder())
                    .isActive(post.getIsActive())
                    .createdAt(post.getCreatedAt())
                    .updatedAt(post.getUpdatedAt())
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Format publik untuk frontend (tanpa metadata internal)")
    public static class PublicResponse {

        @Schema(example = "https://www.instagram.com/p/DXN9TnLkaGW/")
        private String permalink;

        @Schema(example = "Promo Lebaran 2026")
        private String caption;

        @Schema(example = "/uploads/ig/post-1.jpg")
        private String mediaUrl;

        public static PublicResponse from(IgPost post) {
            return PublicResponse.builder()
                    .permalink(post.getPermalink())
                    .caption(post.getCaption() == null ? "" : post.getCaption())
                    .mediaUrl(post.getMediaUrl() == null ? "" : post.getMediaUrl())
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Error response")
    public static class ErrorResponse {
        @Schema(example = "404")
        private int status;

        @Schema(example = "Post tidak ditemukan: id=99")
        private String message;
    }
}
