package com.aiknowledge.user.storage;

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
import java.util.Base64;
import java.util.UUID;

@Service
public class UserAvatarStorageService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final String storageMode;
    private final Path localRoot;
    private final String bucket;
    private final MinioClient minioClient;

    public UserAvatarStorageService(
            @Value("${user.avatar.storage-mode:local}") String storageMode,
            @Value("${user.avatar.local-root:../data/user-avatars}") String localRoot,
            @Value("${user.avatar.minio.endpoint:http://127.0.0.1:9000}") String endpoint,
            @Value("${user.avatar.minio.bucket:ai-user-avatar}") String bucket,
            @Value("${user.avatar.minio.access-key:aiknowledge}") String accessKey,
            @Value("${user.avatar.minio.secret-key:ai-knowledge-local-change-me}") String secretKey
    ) {
        this.storageMode = storageMode;
        this.localRoot = Paths.get(localRoot).toAbsolutePath().normalize();
        this.bucket = bucket;
        this.minioClient = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    public String save(byte[] bytes) {
        ImageType type = detectType(bytes);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("avatar must not exceed 5 MB");
        String objectName = UUID.randomUUID() + "." + type.extension();
        try {
            if ("minio".equalsIgnoreCase(storageMode)) {
                ensureBucket();
                minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectName)
                        .contentType(type.contentType()).stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
            } else {
                Files.createDirectories(localRoot);
                Files.write(localPath(objectName), bytes);
            }
            return "/user/avatar/" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectName.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("failed to store avatar", error);
        }
    }

    public StoredAvatar read(String token) {
        try {
            String objectName = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!objectName.matches("[a-f0-9-]+\\.(jpg|png|gif|webp)")) throw new IllegalArgumentException("invalid avatar reference");
            byte[] bytes;
            if ("minio".equalsIgnoreCase(storageMode)) {
                try (var stream = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
                    bytes = stream.readAllBytes();
                }
            } else {
                bytes = Files.readAllBytes(localPath(objectName));
            }
            return new StoredAvatar(bytes, detectType(bytes).contentType());
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to read avatar", error);
        }
    }

    private Path localPath(String objectName) {
        Path target = localRoot.resolve(objectName).normalize();
        if (!target.startsWith(localRoot)) throw new IllegalArgumentException("invalid avatar path");
        return target;
    }

    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private ImageType detectType(byte[] bytes) {
        if (bytes.length == 0) throw new IllegalArgumentException("avatar file is empty");
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return new ImageType("jpg", "image/jpeg");
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return new ImageType("png", "image/png");
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') return new ImageType("gif", "image/gif");
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return new ImageType("webp", "image/webp");
        throw new IllegalArgumentException("only JPEG, PNG, GIF and WebP avatars are supported");
    }

    public record StoredAvatar(byte[] bytes, String contentType) {}
    private record ImageType(String extension, String contentType) {}
}
