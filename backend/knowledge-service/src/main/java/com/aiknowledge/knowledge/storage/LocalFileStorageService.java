package com.aiknowledge.knowledge.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
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
    private final MinioClient minioClient;

    @Autowired
    public LocalFileStorageService(
            @Value("${knowledge.storage.local-root:../data/uploads}") String localRoot,
            @Value("${knowledge.storage.mode:local}") String storageMode,
            @Value("${knowledge.storage.minio.endpoint:http://127.0.0.1:9000}") String minioEndpoint,
            @Value("${knowledge.storage.minio.bucket:ai-knowledge}") String minioBucket,
            @Value("${knowledge.storage.minio.access-key:aiknowledge}") String minioAccessKey,
            @Value("${knowledge.storage.minio.secret-key:ai-knowledge-local-change-me}") String minioSecretKey
    ) {
        this.storageRoot = Paths.get(localRoot).toAbsolutePath().normalize();
        this.storageMode = storageMode;
        this.minioEndpoint = minioEndpoint;
        this.minioBucket = minioBucket;
        this.minioClient = MinioClient.builder().endpoint(minioEndpoint).credentials(minioAccessKey, minioSecretKey).build();
    }

    public LocalFileStorageService(String localRoot, String storageMode, String minioEndpoint, String minioBucket) {
        this(localRoot, storageMode, minioEndpoint, minioBucket, "aiknowledge", "ai-knowledge-local-change-me");
    }

    public Map<String, Object> saveTextFile(String filename, String content, String fileType) {
        return saveFile(filename, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8", fileType);
    }

    public Map<String, Object> saveFile(String filename, byte[] content, String contentType, String fileType) {
        String safeName = safeFilename(filename, fileType);
        String objectName = DATE_PATH.format(LocalDateTime.now()) + "/" + UUID.randomUUID() + "-" + safeName;
        if ("minio".equalsIgnoreCase(storageMode)) {
            saveToMinio(objectName, content, contentType);
        } else {
            saveLocally(objectName, content);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storageMode", storageMode);
        result.put("objectName", objectName.replace("\\", "/"));
        result.put("fileUrl", "storage://" + objectName.replace("\\", "/"));
        result.put("filename", safeName);
        result.put("size", content.length);
        result.put("contentType", contentType == null ? "application/octet-stream" : contentType);
        result.put("minioReady", "minio".equalsIgnoreCase(storageMode));
        return result;
    }

    public StoredContent read(String fileUrl) {
        String objectName = objectName(fileUrl);
        try {
            if ("minio".equalsIgnoreCase(storageMode)) {
                ensureBucket();
                try (var stream = minioClient.getObject(GetObjectArgs.builder().bucket(minioBucket).object(objectName).build())) {
                    return new StoredContent(stream.readAllBytes(), objectName);
                }
            }
            Path target = storageRoot.resolve(objectName).normalize();
            if (!target.startsWith(storageRoot)) throw new IllegalArgumentException("invalid storage path");
            return new StoredContent(Files.readAllBytes(target), objectName);
        } catch (Exception error) {
            throw new IllegalStateException("failed to read stored file", error);
        }
    }

    public boolean delete(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) return false;
        String objectName = objectName(fileUrl);
        try {
            if ("minio".equalsIgnoreCase(storageMode)) {
                ensureBucket();
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(minioBucket).object(objectName).build());
                return true;
            }
            Path target = storageRoot.resolve(objectName).normalize();
            if (!target.startsWith(storageRoot)) throw new IllegalArgumentException("invalid storage path");
            return Files.deleteIfExists(target);
        } catch (Exception error) {
            throw new IllegalStateException("failed to delete stored file", error);
        }
    }

    private void saveLocally(String objectName, byte[] content) {
        Path target = storageRoot.resolve(objectName).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("invalid storage path");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException error) {
            throw new IllegalStateException("failed to save local file", error);
        }
    }

    private void saveToMinio(String objectName, byte[] content, String contentType) {
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(objectName)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .build());
        } catch (Exception error) {
            throw new IllegalStateException("failed to save file to MinIO", error);
        }
    }

    private void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(minioBucket).build());
        if (!exists) minioClient.makeBucket(MakeBucketArgs.builder().bucket(minioBucket).build());
    }

    private String objectName(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) throw new IllegalArgumentException("file has no storage reference");
        if (fileUrl.startsWith("storage://")) return fileUrl.substring("storage://".length());
        if (fileUrl.startsWith("local-file://")) return fileUrl.substring("local-file://".length());
        throw new IllegalArgumentException("unsupported storage reference");
    }

    public Map<String, Object> status() {
        boolean minioReady = false;
        if ("minio".equalsIgnoreCase(storageMode)) {
            try {
                ensureBucket();
                minioReady = true;
            } catch (Exception ignored) {
                minioReady = false;
            }
        }
        return Map.of(
                "mode", storageMode,
                "localRoot", storageRoot.toString(),
                "minioEndpoint", minioEndpoint,
                "minioBucket", minioBucket,
                "minioReady", minioReady
        );
    }

    private String safeFilename(String filename, String fileType) {
        String fallbackExt = fileType == null || fileType.isBlank() ? "txt" : fileType.replaceAll("[^A-Za-z0-9]", "");
        String base = filename == null || filename.isBlank() ? "knowledge." + fallbackExt : filename;
        return base.replaceAll("[\\\\/:*?\\\"<>|]", "_");
    }

    public record StoredContent(byte[] bytes, String objectName) {
    }
}
