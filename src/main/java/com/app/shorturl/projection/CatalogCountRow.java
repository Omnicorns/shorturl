package com.app.shorturl.projection;

import java.time.LocalDateTime;

/** Baris ringan untuk polling live kolom AKSES (id, jumlah akses, waktu terakhir). */
public interface CatalogCountRow {
    Long getId();
    Long getAccessCount();
    LocalDateTime getLastAccessedAt();
}
