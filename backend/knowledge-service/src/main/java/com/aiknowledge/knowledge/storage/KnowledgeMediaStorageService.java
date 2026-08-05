package com.aiknowledge.knowledge.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

@Service
public class KnowledgeMediaStorageService {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final String storageMode;
    private final Path localRoot;
    private final String bucket;
    private final MinioClient minioClient;

    public KnowledgeMediaStorageService(
            @Value("${knowledge.storage.mode:local}") String storageMode,
            @Value("${knowledge.media.local-root:../data/knowledge-media}") String localRoot,
            @Value("${knowledge.storage.minio.endpoint:http://127.0.0.1:9000}") String endpoint,
            @Value("${knowledge.storage.minio.bucket:ai-knowledge}") String bucket,
            @Value("${knowledge.storage.minio.access-key:aiknowledge}") String accessKey,
            @Value("${knowledge.storage.minio.secret-key:ai-knowledge-local-change-me}") String secretKey
    ) {
        this.storageMode = storageMode;
        this.localRoot = Paths.get(localRoot).toAbsolutePath().normalize();
        this.bucket = bucket;
        this.minioClient = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    public String save(byte[] bytes) {
        ImageType type = validate(bytes);
        String objectName = "media/" + LocalDate.now().format(DATE_PATH) + "/" + UUID.randomUUID() + "." + type.extension();
        try {
            if ("minio".equalsIgnoreCase(storageMode)) {
                ensureBucket();
                minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectName)
                        .contentType(type.contentType())
                        .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
            } else {
                Path target = localPath(objectName);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            }
            return "/knowledge/media/" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectName.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("failed to store knowledge image", error);
        }
    }

    public ImageType validate(byte[] bytes) {
        if (bytes.length == 0) throw new IllegalArgumentException("image file is empty");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("each image must not exceed 10 MB");
        return detectType(bytes);
    }

    public StoredMedia read(String token) {
        try {
            String objectName = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!objectName.matches("media/\\d{4}/\\d{2}/\\d{2}/[a-f0-9-]+\\.(jpg|png|gif|webp)")) {
                throw new IllegalArgumentException("invalid media reference");
            }
            byte[] bytes;
            if ("minio".equalsIgnoreCase(storageMode)) {
                try (var stream = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
                    bytes = stream.readAllBytes();
                }
            } else {
                bytes = Files.readAllBytes(localPath(objectName));
            }
            return new StoredMedia(bytes, detectType(bytes).contentType());
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to read knowledge image", error);
        }
    }

    private Path localPath(String objectName) {
        Path target = localRoot.resolve(objectName).normalize();
        if (!target.startsWith(localRoot)) throw new IllegalArgumentException("invalid media path");
        return target;
    }

    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private ImageType detectType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return new ImageType("jpg", "image/jpeg");
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return new ImageType("png", "image/png");
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') return new ImageType("gif", "image/gif");
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return new ImageType("webp", "image/webp");
        throw new IllegalArgumentException("only JPEG, PNG, GIF and WebP images are supported");
    }

    public record StoredMedia(byte[] bytes, String contentType) {}
    public record ImageType(String extension, String contentType) {}
}
