# Nusantara Link — Short URL + Landing Page Kebudayaan

Aplikasi Spring Boot + Thymeleaf dengan 3 layer:
- **Landing page publik** — user bebas bikin short URL, tema kebudayaan Indonesia
- **Dashboard admin** — kelola semua short URL
- **REST API + Swagger UI** — akses via HTTP JSON untuk integrasi

## Tech Stack
- Spring Boot 3.2.5 (Java 17)
- Spring Security (form login + Basic Auth API)
- Spring Data JPA + H2 Database
- Thymeleaf
- **SpringDoc OpenAPI 2.3.0** (Swagger UI)
- Lombok, Maven

## Cara Menjalankan

```bash
mvn spring-boot:run
# atau
mvn clean package && java -jar target/shorturl-1.0.0.jar
```

App jalan di **http://localhost:8080**

## Peta URL

| URL                          | Keterangan                              |
|------------------------------|-----------------------------------------|
| `/`                          | Landing page publik (form shortener)    |
| `/login`                     | Halaman login admin                     |
| `/admin`                     | Dashboard admin (perlu login)           |
| `/{kode}`                    | Redirect 302 ke URL asli                |
| **`/swagger-ui.html`**       | **Swagger UI — dokumentasi & testing**  |
| `/v3/api-docs`               | OpenAPI spec JSON (raw)                 |
| `/h2-console`                | H2 database console (development)       |

**Default credentials:** `admin` / `admin123`

---

## Dokumentasi Swagger

Buka **http://localhost:8080/swagger-ui.html** setelah aplikasi jalan.

### Fitur Swagger UI

1. **Liat semua endpoint** yang tersedia, terbagi jadi:
   - **Public API** — tanpa authentication
   - **Admin API** — butuh Basic Auth

2. **Try it out** — bisa test endpoint langsung dari browser:
   - Klik endpoint → "Try it out" → isi body → "Execute"
   - Response langsung muncul dengan status code, headers, body

3. **Authorize untuk Admin API:**
   - Klik tombol **"Authorize"** (gembok) di kanan atas
   - Masukkan username `admin`, password `admin123`
   - Klik "Authorize" → semua endpoint admin otomatis ke-auth

### Endpoint yang Tersedia

#### Public API (no auth)

**POST** `/api/v1/shorten` — Buat short URL
```json
{
  "originalUrl": "https://id.wikipedia.org/wiki/Batik",
  "title": "Artikel Batik Indonesia",
  "customCode": "batik-id"
}
```
Response 201:
```json
{
  "id": 1,
  "shortCode": "batik-id",
  "shortUrl": "http://localhost:8080/batik-id",
  "originalUrl": "https://id.wikipedia.org/wiki/Batik",
  "title": "Artikel Batik Indonesia",
  "clickCount": 0,
  "active": true,
  "createdAt": "2026-04-20T10:30:00",
  "lastAccessedAt": null
}
```

**GET** `/api/v1/info/{code}` — Info short URL (tanpa increment klik)

#### Admin API (Basic Auth)

**GET** `/api/v1/admin/urls?q=batik&page=0&size=20` — List + search + pagination

**GET** `/api/v1/admin/stats` — Statistik total

**PATCH** `/api/v1/admin/urls/{id}/toggle` — Aktifkan/nonaktifkan

**DELETE** `/api/v1/admin/urls/{id}` — Hapus permanen

### Contoh cURL

```bash
# Public: bikin short URL
curl -X POST http://localhost:8080/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{"originalUrl":"https://kemdikbud.go.id","title":"Kemdikbud"}'

# Public: cek info
curl http://localhost:8080/api/v1/info/batik-id

# Admin: list semua URL
curl -u admin:admin123 http://localhost:8080/api/v1/admin/urls

# Admin: stats
curl -u admin:admin123 http://localhost:8080/api/v1/admin/stats

# Admin: toggle (aktif <-> nonaktif)
curl -u admin:admin123 -X PATCH http://localhost:8080/api/v1/admin/urls/1/toggle

# Admin: hapus
curl -u admin:admin123 -X DELETE http://localhost:8080/api/v1/admin/urls/1

# Akses short URL (otomatis redirect, tracking klik)
curl -L http://localhost:8080/batik-id
```

---

## Struktur Project

```
src/main/java/com/app/shorturl/
├── ShorturlApplication.java
├── config/
│   ├── SecurityConfig.java         # 2 filter chain: API & web
│   └── OpenApiConfig.java          # Config Swagger/OpenAPI
├── controller/
│   ├── PublicController.java       # Landing, redirect (HTML)
│   ├── AdminController.java        # Dashboard (HTML)
│   ├── ShortUrlApiController.java  # REST Public API
│   └── AdminApiController.java     # REST Admin API
├── dto/
│   └── ShortUrlDto.java            # Request/Response DTO
├── model/
│   └── ShortUrl.java
├── repository/
│   └── ShortUrlRepository.java
└── service/
    └── ShortUrlService.java
```

## Arsitektur Security

Ada **2 filter chain terpisah**:

| Chain    | Path       | Auth         | Session     | CSRF   |
|----------|------------|--------------|-------------|--------|
| API (1)  | `/api/**`  | Basic Auth   | Stateless   | Off    |
| Web (2)  | lainnya    | Form Login   | Session     | On     |

Ini bikin API bisa dipanggil oleh client eksternal (Postman, mobile, dll) tanpa harus nge-handle CSRF token, sementara UI web tetap pakai session-based auth yang aman.

## Catatan Production

1. Ganti `app.admin.password` dari default
2. Ganti H2 ke PostgreSQL/MySQL
3. Matikan H2 console: `spring.h2.console.enabled=false`
4. Pertimbangkan matikan Swagger UI: `springdoc.swagger-ui.enabled=false`
5. Ganti Basic Auth ke JWT/OAuth2 untuk API production
6. Pasang HTTPS + reverse proxy (nginx/Caddy)

## Konfigurasi keamanan (wajib di production)

Semua rahasia dibaca dari environment variable — **jangan** menulis password di `application.properties`.

| Variable | Keterangan |
|---|---|
| `APP_ADMIN_USERNAME`, `APP_ADMIN_PASSWORD` | Akun emergency lokal (super-admin). Password boleh hash BCrypt. Kosong = akun emergency nonaktif. |
| `LDAP_BIND_DN`, `LDAP_BIND_PASSWORD` | Service account AD untuk pencarian user. |
| `LDAP_ADMIN_GROUPS` | CN group AD (CSV) yang boleh login ke dashboard. **Kosong = tidak ada user AD yang bisa login.** |
| `LDAP_SUPERADMIN_GROUPS` | CN group AD (CSV) yang otomatis super-admin. |
| `APP_TRACKING_SECRET` | Secret HMAC token tracking klik (string acak ≥ 32 karakter). |
| `APP_PDF_ALLOW_LEGACY_ID_LINKS` | `false` (default): PDF hanya bisa dibuka publik dengan kunci `?k=`. |
| `LDAP_URL`, `LDAP_DOMAIN`, `LDAP_BASE_DN` | Opsional, default sama seperti sebelumnya. |

Catatan:
- Pembuatan akun (`/register`) dan link reset password (`/forgot-password`) hanya untuk super-admin.
  Link reset ditampilkan sekali ke super-admin, berlaku 30 menit, sekali pakai, dan tidak ditulis ke log.
- Set `app.base-url` agar link reset & short URL memakai domain resmi.
- Link catalog sekarang berbentuk `/catalogue?id=N&k=KUNCI`. Saat startup, short URL lama yang mengarah
  ke `/catalogue?id=N` otomatis ditambah kuncinya (QR/short URL lama tetap berfungsi).
