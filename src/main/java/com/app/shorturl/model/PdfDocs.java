package com.app.shorturl.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "pdf_docs")
@Getter
@Setter
public class PdfDocs {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String filename;
    private String contentType = "application/pdf";

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data") // Postgres: bytea, MySQL: LONGBLOB, dll
    private byte[] data;
}
