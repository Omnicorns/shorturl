package com.app.shorturl.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "pdf_docs")
@Getter
@Setter
public class PdfDocs {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String filename;
    private String contentType = "application/pdf";

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data") // Postgres: bytea, MySQL: LONGBLOB, dll
    private byte[] data;


    @Column(name = "owner_username", length = 100)
    private String ownerUsername;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "pdf_doc_co_managers",
            joinColumns = @JoinColumn(name = "pdf_doc_id"),
            indexes = @Index(name = "idx_pdf_co_manager_username", columnList = "username")
    )
    @Column(name = "username", length = 100, nullable = false)
    private Set<String> coManagers = new HashSet<>();

    @PrePersist
    public void prePersist() {
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/pdf";
        }
        if (coManagers == null) {
            coManagers = new HashSet<>();
        }
        if (ownerUsername != null) {
            ownerUsername = ownerUsername.trim().toLowerCase();
        }
    }

    public boolean isAccessibleBy(String username) {
        if (username == null) return false;

        String u = username.trim().toLowerCase();

        if (ownerUsername != null && ownerUsername.equalsIgnoreCase(u)) {
            return true;
        }

        if (coManagers != null) {
            for (String cm : coManagers) {
                if (cm != null && cm.equalsIgnoreCase(u)) {
                    return true;
                }
            }
        }

        return false;
    }
}
