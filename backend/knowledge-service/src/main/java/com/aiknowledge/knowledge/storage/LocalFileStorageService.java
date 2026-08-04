package com.aiknowledge.knowledge.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class LocalFileStorageService {
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Path storageRoot;
    private final String storageMode;
    private final String minioEndpoint;
    private final String minioBucket;

    public LocalFileStorageService(
            @Value("${knowledge.storage.local-root:../data/uploads}") String localRoot,
            @Value("${knowledge.storage.mode:local}") String storageMode,
            @Value("${knowledge.storage.minio.endpoint:http://127.0.0.1:9000}") String minioEndpoint,
            @Value("${knowledge.storage.minio.bucket:ai-knowledge}") String minioBucket
    ) {
        this.storageRoot = Paths.get(localRoot).toAbsolutePath().normalize();
        this.storageMode = storageMode;
        this.minioEndpoint = minioEndpoint;
        this.minioBucket = minioBucket;
    }

    public Map<String, Object> saveTextFile(String filename, String content, String fileType) {
        String safeName = safeFilename(filename, fileType);
        String objectName = DATE_PATH.format(LocalDateTime.now()) + "/" + UUID.randomUUID() + "-" + safeName;
        Path target = storageRoot.resolve(objectName).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("invalid storage path");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("failed to save local file", error);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storageMode", storageMode);
        result.put("objectName", objectName.replace("\\", "/"));
        result.put("fileUrl", "local-file://" + objectName.replace("\\", "/"));
        result.put("filename", safeName);
        result.put("size", target.toFile().length());
        result.put("localPath", target.toString());
        result.put("minioReady", !"local".equalsIgnoreCase(storageMode));
        return result;
    }

    public Map<String, Object> status() {
        return Map.of(
                "mode", storageMode,
                "localRoot", storageRoot.toString(),
                "minioEndpoint", minioEndpoint,
                "minioBucket", minioBucket,
                "minioReady", !"local".equalsIgnoreCase(storageMode)
        );
    }

    private String safeFilename(String filename, String fileType) {
        String fallbackExt = fileType == null || fileType.isBlank() ? "txt" : fileType.replaceAll("[^A-Za-z0-9]", "");
        String base = filename == null || filename.isBlank() ? "knowledge." + fallbackExt : filename;
        return base.replaceAll("[\\\\/:*?\\\"<>|]", "_");
    }
}
