package com.app.shorturl.controller;

import com.app.shorturl.dto.ShortUrlDto;
import com.app.shorturl.dto.ShortUrlDto.CreateRequest;
import com.app.shorturl.dto.ShortUrlDto.ErrorResponse;
import com.app.shorturl.dto.ShortUrlDto.Response;
import com.app.shorturl.model.ShortUrl;
import com.app.shorturl.repository.ShortUrlRepository;
import com.app.shorturl.service.ShortUrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Public API", description = "Endpoint publik untuk membuat & mengakses short URL (tanpa auth)")
@SecurityRequirements  // kosongin auth untuk endpoint di tag ini
public class ShortUrlApiController {

    private final ShortUrlService service;
    private final ShortUrlRepository repository;

    @Value("${app.base-url}")
    private String baseUrl;

    @Operation(
        summary = "Buat short URL baru",
        description = "Membuat short URL dari URL panjang. Custom code opsional — " +
                     "jika kosong, kode 7 karakter akan di-generate otomatis."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Short URL berhasil dibuat",
            content = @Content(schema = @Schema(implementation = Response.class))),
        @ApiResponse(responseCode = "400", description = "Input tidak valid",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/shorten")
    public ResponseEntity<?> shorten(@Valid @RequestBody CreateRequest request) {
        try {
            ShortUrl created = service.create(
                    request.getOriginalUrl(),
                    request.getTitle(),
                    request.getCustomCode()
            );
            return ResponseEntity.status(201).body(Response.from(created, baseUrl));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(400, e.getMessage()));
        }
    }

    @Operation(
        summary = "Info short URL",
        description = "Mendapatkan detail short URL berdasarkan kode (tanpa tracking klik)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Short URL ditemukan",
            content = @Content(schema = @Schema(implementation = Response.class))),
        @ApiResponse(responseCode = "404", description = "Short URL tidak ditemukan",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/info/{code}")
    public ResponseEntity<?> info(@PathVariable String code) {
        Optional<ShortUrl> opt = repository.findByShortCode(code);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(404, "Short URL tidak ditemukan: " + code));
        }
        return ResponseEntity.ok(Response.from(opt.get(), baseUrl));
    }
}
