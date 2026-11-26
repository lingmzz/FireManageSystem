package com.firemanage;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import static com.firemanage.ApiSupport.invalidField;
import static com.firemanage.ApiSupport.missing;

@Service
class UserProfiles {
    private static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENCODED_LENGTH = (MAX_UPLOAD_BYTES + 2) / 3 * 4;
    private static final int MAX_IMAGE_SIDE = 4096;
    private static final int AVATAR_SIDE = 256;
    private static final Set<String> FORMATS = Set.of("jpeg", "png");
    static final Profile EMPTY = new Profile(null, null, null, null, null);

    private final JdbcClient jdbc;

    UserProfiles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Map<Long, Profile> find(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.sql("""
                SELECT p.user_id, p.unit_id, u.name AS unit_name, p.phone, p.email,
                       IF(p.avatar IS NULL, NULL, p.avatar_version) AS avatar_version
                FROM fm_user_profile p LEFT JOIN fm_fire_unit u ON u.id = p.unit_id
                WHERE p.user_id IN (:ids)
                """).param("ids", ids).query(ProfileRow.class).list()
                .stream().collect(Collectors.toMap(ProfileRow::userId, ProfileRow::view));
    }

    void save(long id, Edit personal, String fieldPrefix) {
        byte[] image = personal.avatar() == null || personal.avatar().isEmpty()
                ? null : avatar(personal.avatar(), fieldPrefix + "avatar");
        jdbc.sql("""
                INSERT INTO fm_user_profile (user_id, phone, email) VALUES (:id, :phone, :email)
                ON DUPLICATE KEY UPDATE phone = :phone, email = :email
                """).param("id", id).param("phone", clean(personal.phone()))
                .param("email", clean(personal.email())).update();
        if (personal.avatar() != null) {
            jdbc.sql("UPDATE fm_user_profile SET avatar = :avatar, avatar_version = avatar_version + 1 WHERE user_id = :id")
                    .param("id", id).param("avatar", image).update();
        }
    }

    void saveUnit(long id, Long unitId) {
        if (unitId != null && jdbc.sql("SELECT id FROM fm_fire_unit WHERE id = :id")
                .param("id", unitId).query(Long.class).optional().isEmpty()) {
            throw invalidField("profile.unitId", "请选择有效的所属单位");
        }
        jdbc.sql("UPDATE fm_user_profile SET unit_id = :unit WHERE user_id = :id")
                .param("id", id).param("unit", unitId).update();
    }

    byte[] image(long id) {
        return jdbc.sql("SELECT avatar FROM fm_user_profile WHERE user_id = :id AND avatar IS NOT NULL")
                .param("id", id).query((rs, row) -> rs.getBytes("avatar")).optional()
                .orElseThrow(() -> missing("头像不存在"));
    }

    private static byte[] avatar(String encoded, String field) {
        try {
            byte[] uploaded = Base64.getDecoder().decode(encoded);
            if (uploaded.length > MAX_UPLOAD_BYTES) throw invalidField(field, "头像大小不能超过 2 MB");
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(uploaded))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw invalidField(field, "请选择有效的 PNG 或 JPEG 图片");
                var reader = readers.next();
                try {
                    if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                        throw invalidField(field, "请选择 PNG 或 JPEG 图片");
                    }
                    reader.setInput(stream, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (Math.max(width, height) > MAX_IMAGE_SIDE) {
                        throw invalidField(field, "头像宽高不能超过 4096 像素");
                    }
                    double scale = Math.min(1, AVATAR_SIDE / (double) Math.max(width, height));
                    var resized = new BufferedImage(Math.max(1, (int) Math.round(width * scale)),
                            Math.max(1, (int) Math.round(height * scale)), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = resized.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(reader.read(0), 0, 0, resized.getWidth(), resized.getHeight(), null);
                    } finally {
                        graphics.dispose();
                    }
                    var output = new ByteArrayOutputStream();
                    ImageIO.write(resized, "png", output);
                    return output.toByteArray();
                } finally {
                    reader.dispose();
                }
            }
        } catch (IllegalArgumentException | IOException failure) {
            throw invalidField(field, "头像图片无法读取");
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    record Profile(Long unitId, String unitName, String phone, String email, String avatarUrl) {}
    record ProfileRow(long userId, Long unitId, String unitName, String phone, String email, Long avatarVersion) {
        Profile view() {
            String url = avatarVersion == null ? null : "/api/users/" + userId + "/avatar?v=" + avatarVersion;
            return new Profile(unitId, unitName, phone, email, url);
        }
    }

    record Edit(@Size(max = 40, message = "联系电话最多 40 个字符") String phone,
                @Email(message = "请输入有效的电子邮箱") @Size(max = 254, message = "电子邮箱最多 254 个字符") String email,
                @Size(max = MAX_ENCODED_LENGTH, message = "头像大小不能超过 2 MB") String avatar) {}
    record Administration(@Positive(message = "请选择有效的所属单位") Long unitId, @NotNull @Valid Edit personal) {}
}
