package com.aiknowledge.knowledge.storage;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class DocumentTextExtractor {
    private static final int MAX_EMBEDDED_IMAGES = 50;
    private static final long MAX_EMBEDDED_IMAGE_BYTES = 50L * 1024 * 1024;

    public String extract(String filename, byte[] data) {
        return parse(filename, data).text();
    }

    public ParsedDocument parse(String filename, byte[] data) {
        String extension = extension(filename);
        try {
            return switch (extension) {
                case "txt", "md", "markdown", "csv" ->
                        new ParsedDocument(new String(data, StandardCharsets.UTF_8), List.of());
                case "pdf" -> new ParsedDocument(extractPdf(data), List.of());
                case "docx" -> parseDocx(data);
                default -> throw new IllegalArgumentException("unsupported file type: " + extension);
            };
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to extract document text", error);
        }
    }

    private ParsedDocument parseDocx(byte[] data) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(data));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            List<ParsedBlock> blocks = new ArrayList<>();
            ParseContext context = new ParseContext();
            parseBodyElements(document.getBodyElements(), blocks, context);
            return new ParsedDocument(extractor.getText(), blocks);
        }
    }

    private void parseBodyElements(List<IBodyElement> elements, List<ParsedBlock> blocks, ParseContext context) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                parseParagraph(paragraph, blocks, context);
            } else if (element instanceof XWPFTable table) {
                parseTable(table, blocks, context);
            }
        }
    }

    private void parseTable(XWPFTable table, List<ParsedBlock> blocks, ParseContext context) {
        List<String> rows = new ArrayList<>();
        List<ParsedBlock> tableImages = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = row.getTableCells().stream()
                    .map(cell -> cell.getText().replace('\t', ' ').replaceAll("[\\r\\n]+", " ").trim())
                    .toList();
            if (cells.stream().anyMatch(value -> !value.isBlank())) rows.add(String.join("\t", cells));
            for (XWPFTableCell cell : row.getTableCells()) {
                for (IBodyElement bodyElement : cell.getBodyElements()) {
                    if (bodyElement instanceof XWPFParagraph paragraph) {
                        for (XWPFRun run : paragraph.getRuns()) {
                            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                                XWPFPictureData pictureData = picture.getPictureData();
                                if (pictureData == null) continue;
                                byte[] bytes = pictureData.getData();
                                context.registerImage(bytes.length);
                                String description = picture.getDescription();
                                if (description == null || description.isBlank()) description = pictureData.getFileName();
                                tableImages.add(ParsedBlock.image(description, bytes));
                            }
                        }
                    }
                }
            }
        }
        if (!rows.isEmpty()) blocks.add(ParsedBlock.text("table", String.join("\n", rows)));
        blocks.addAll(tableImages);
    }

    private void parseParagraph(XWPFParagraph paragraph, List<ParsedBlock> blocks, ParseContext context) {
        String type = paragraphType(paragraph);
        StringBuilder text = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (runText != null && !runText.isBlank()) {
                if (!text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1))) text.append(' ');
                text.append(runText.trim());
            }
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                flushTextBlock(blocks, type, text);
                XWPFPictureData pictureData = picture.getPictureData();
                if (pictureData == null) continue;
                byte[] bytes = pictureData.getData();
                context.registerImage(bytes.length);
                String description = picture.getDescription();
                if (description == null || description.isBlank()) description = pictureData.getFileName();
                blocks.add(ParsedBlock.image(description, bytes));
            }
        }
        if (paragraph.getRuns().isEmpty()) text.append(paragraph.getText());
        flushTextBlock(blocks, type, text);
    }

    private void flushTextBlock(List<ParsedBlock> blocks, String type, StringBuilder text) {
        String value = text.toString().trim();
        if (!value.isBlank()) blocks.add(ParsedBlock.text(type, value));
        text.setLength(0);
    }

    private String paragraphType(XWPFParagraph paragraph) {
        if (paragraph.getNumID() != null) return "list";
        String style = paragraph.getStyle();
        if (style != null) {
            String normalized = style.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("heading") || normalized.startsWith("title") || normalized.startsWith("标题")) {
                return "heading";
            }
        }
        return "paragraph";
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

    public record ParsedDocument(String text, List<ParsedBlock> blocks) {
        public ParsedDocument {
            text = text == null ? "" : text;
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }
    }

    public record ParsedBlock(String type, String text, byte[] imageBytes) {
        public ParsedBlock {
            imageBytes = imageBytes == null ? null : imageBytes.clone();
        }

        public static ParsedBlock text(String type, String text) {
            return new ParsedBlock(type, text, null);
        }

        public static ParsedBlock image(String description, byte[] bytes) {
            return new ParsedBlock("image", description, bytes);
        }
    }

    private static class ParseContext {
        private int images;
        private long imageBytes;

        void registerImage(long bytes) {
            images++;
            imageBytes += bytes;
            if (images > MAX_EMBEDDED_IMAGES) {
                throw new IllegalArgumentException("Word 文档中的图片不能超过 " + MAX_EMBEDDED_IMAGES + " 张");
            }
            if (imageBytes > MAX_EMBEDDED_IMAGE_BYTES) {
                throw new IllegalArgumentException("Word 文档解压后的图片总大小不能超过 50 MB");
            }
        }
    }
}
