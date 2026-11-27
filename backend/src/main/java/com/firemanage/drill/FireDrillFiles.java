package com.firemanage.drill;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import lombok.Value;
import org.apache.commons.io.IOUtils;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import static com.firemanage.common.ApiSupport.bad;
import static com.firemanage.common.ApiSupport.missing;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.strip;

@Component
class FireDrillFiles {
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private final Path root;

    FireDrillFiles(Environment environment) {
        root = Paths.get(environment.getProperty("fire.drill-directory", ".local/fire-drill-files"))
                .toAbsolutePath().normalize();
    }

    Saved save(FireDrillController.Kind kind, MultipartFile file) throws IOException {
        String name = originalName(file);
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw bad("附件大小须在 8 MB 以内");
        Files.createDirectories(root);
        String key = UUID.randomUUID() + ".bin";
        Path output = path(key);
        try (BufferedInputStream input = new BufferedInputStream(file.getInputStream())) {
            input.mark(16);
            byte[] head = new byte[12];
            int headerSize = IOUtils.read(input, head);
            input.reset();
            String type = contentType(kind, name, Arrays.copyOf(head, headerSize));
            long size = Files.copy(input, output);
            if (size > MAX_BYTES) throw bad("附件大小须在 8 MB 以内");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) delete(key);
                }
            });
            return new Saved(key, name, type, size);
        } catch (IOException | RuntimeException failure) {
            delete(key);
            throw failure;
        }
    }

    FileSystemResource load(String key) {
        Path file = path(key);
        if (!Files.isRegularFile(file)) throw missing("附件文件不存在");
        return new FileSystemResource(file);
    }

    void deleteAfterCommit(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                delete(key);
            }
        });
    }

    private void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException failure) {
            LoggerFactory.getLogger(FireDrillFiles.class).warn("无法清理附件 {}", key, failure);
        }
    }

    private Path path(String key) {
        if (!key.matches("[0-9a-f-]{36}\\.bin")) throw missing("附件不存在");
        return root.resolve(key);
    }

    private static String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) throw bad("附件需要文件名");
        String normalized = name.replace('\\', '/');
        String basename = strip(normalized.substring(normalized.lastIndexOf('/') + 1));
        if (isBlank(basename) || basename.length() > 180 || basename.chars().anyMatch(Character::isISOControl)) {
            throw bad("附件文件名无效");
        }
        return basename;
    }

    private static String contentType(FireDrillController.Kind kind, String name, byte[] bytes) {
        String extension = name.substring(Math.max(0, name.lastIndexOf('.') + 1)).toLowerCase(Locale.ROOT);
        if (kind == FireDrillController.Kind.PHOTO) {
            switch (extension) {
                case "jpg":
                case "jpeg":
                    return matches(bytes, 0, 0xff, 0xd8, 0xff) ? "image/jpeg" : invalidImage();
                case "png":
                    return matches(bytes, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ? "image/png" : invalidImage();
                case "webp":
                    return matches(bytes, 0, 'R', 'I', 'F', 'F') && matches(bytes, 8, 'W', 'E', 'B', 'P')
                            ? "image/webp" : invalidImage();
                default:
                    return invalidImage();
            }
        }
        switch (extension) {
            case "pdf":
                return matches(bytes, 0, '%', 'P', 'D', 'F', '-') ? "application/pdf" : invalidSummary();
            case "docx":
                return matches(bytes, 0, 'P', 'K', 3, 4)
                        ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : invalidSummary();
            case "doc":
                return matches(bytes, 0, 0xd0, 0xcf, 0x11, 0xe0) ? "application/msword" : invalidSummary();
            case "txt":
                return "text/plain";
            default:
                return invalidSummary();
        }
    }

    private static boolean matches(byte[] bytes, int offset, int... signature) {
        if (offset + signature.length > bytes.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (Byte.toUnsignedInt(bytes[offset + i]) != signature[i]) return false;
        }
        return true;
    }

    private static String invalidImage() {
        throw bad("演练图片请上传 JPEG、PNG 或 WebP 格式");
    }

    private static String invalidSummary() {
        throw bad("演练总结请上传 Word、PDF 或 TXT 文件");
    }

    @Value
    static class Saved {
        String key;
        String name;
        String contentType;
        long size;
    }
}
