package com.app.shorturl.controller;

import com.app.shorturl.model.ClickLog;
import com.app.shorturl.model.ShortUrl;
import com.app.shorturl.repository.ClickLogRepository;
import com.app.shorturl.service.ShortUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequestMapping("/admin/logs")
@RequiredArgsConstructor
public class AdminLogController {

    private final ClickLogRepository repository;
    private final ShortUrlService shortUrlService;

    @GetMapping
    public String logs(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String code,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100)
        );

        Page<ClickLog> logs;
        ShortUrl filteredUrl = null;

        // Prioritas: filter by code (click dari dashboard) > search text > semua
        if (code != null && !code.isBlank()) {
            Optional<ShortUrl> opt = shortUrlService.findByCode(code);
            if (opt.isPresent()) {
                filteredUrl = opt.get();
                logs = repository.findByShortUrlIdOrderByCreatedAtDesc(
                        filteredUrl.getId(), pageable);
            } else {
                logs = Page.empty(pageable);
            }
        } else if (q != null && !q.isBlank()) {
            logs = repository.search(q.trim(), pageable);
        } else {
            logs = repository.findAllByOrderByCreatedAtDesc(pageable);
        }

        model.addAttribute("logs", logs);
        model.addAttribute("q", q);
        model.addAttribute("code", code);
        model.addAttribute("filteredUrl", filteredUrl);
        model.addAttribute("totalLogs", repository.count());

        // Stats: full (tanpa filter) — biar konsisten di header
        model.addAttribute("countryStats", toMap(repository.countByCountry(), 10));
        model.addAttribute("browserStats", toMap(repository.countByBrowser(), 10));
        model.addAttribute("deviceStats", toMap(repository.countByDeviceType(), 10));
        return "admin/logs";
    }

    /**
     * Endpoint JSON untuk dipanggil oleh log drawer di dashboard.
     * Return: { content: [...], totalElements, totalPages, page, size }
     */
    @GetMapping("/data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> logsData(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 500)
        );

        Page<ClickLog> logs;
        if (code != null && !code.isBlank()) {
            Optional<ShortUrl> opt = shortUrlService.findByCode(code);
            if (opt.isEmpty()) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("content", List.of());
                empty.put("totalElements", 0);
                empty.put("totalPages", 0);
                empty.put("page", 0);
                empty.put("size", size);
                return ResponseEntity.ok(empty);
            }
            logs = repository.findByShortUrlIdOrderByCreatedAtDesc(opt.get().getId(), pageable);
        } else if (q != null && !q.isBlank()) {
            logs = repository.search(q.trim(), pageable);
        } else {
            logs = repository.findAllByOrderByCreatedAtDesc(pageable);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("content", logs.getContent());
        body.put("totalElements", logs.getTotalElements());
        body.put("totalPages", logs.getTotalPages());
        body.put("page", logs.getNumber());
        body.put("size", logs.getSize());
        return ResponseEntity.ok(body);
    }

    private Map<String, Long> toMap(List<Object[]> rows, int limit) {
        Map<String, Long> m = new LinkedHashMap<>();
        int count = 0;
        for (Object[] row : rows) {
            if (count++ >= limit) break;
            String key = row[0] != null ? row[0].toString() : "Unknown";
            Long val = ((Number) row[1]).longValue();
            m.put(key, val);
        }
        return m;
    }
}
