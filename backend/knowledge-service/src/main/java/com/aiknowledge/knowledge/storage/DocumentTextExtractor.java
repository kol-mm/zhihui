package com.aiknowledge.knowledge.storage;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class DocumentTextExtractor {
    public String extract(String filename, byte[] data) {
        String extension = extension(filename);
        try {
            return switch (extension) {
                case "txt", "md", "markdown", "csv" -> new String(data, StandardCharsets.UTF_8);
                case "pdf" -> extractPdf(data);
                case "docx" -> extractDocx(data);
                default -> throw new IllegalArgumentException("unsupported file type: " + extension);
            };
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to extract document text", error);
        }
    }

    private String extractDocx(byte[] data) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(data));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractPdf(byte[] data) throws Exception {
        try (PDDocument document = Loader.loadPDF(data)) {
            return new PDFTextStripper().getText(document);
        }
    }

    public String extension(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).replaceAll("[^a-z0-9]", "");
    }
}
