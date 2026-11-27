package com.firemanage.identity;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Blob;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.validation.Valid;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

import com.firemanage.common.JdbcClient;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;
import org.springframework.stereotype.Service;

import static com.firemanage.common.ApiSupport.invalidField;
import static com.firemanage.common.ApiSupport.missing;
import static org.apache.commons.lang3.StringUtils.stripToNull;

@Service
class UserProfiles {
    private static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENCODED_LENGTH = (MAX_UPLOAD_BYTES + 2) / 3 * 4;
    private static final int MAX_IMAGE_SIDE = 4096;
    private static final int AVATAR_SIDE = 256;
    private static final Set<String> FORMATS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("jpeg", "png")));
    static final Profile EMPTY = new Profile(null, null, null, null, null);

    private final JdbcClient jdbc;

    UserProfiles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Map<Long, Profile> find(List<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        return jdbc.sql("SELECT p.user_id, p.unit_id, u.name AS unit_name, p.phone, p.email,\n"
                + "       CASE WHEN p.avatar IS NULL THEN NULL ELSE p.avatar_version END AS avatar_version\n"
                + "FROM fm_user_profile p LEFT JOIN fm_fire_unit u ON u.id = p.unit_id\n"
                + "WHERE p.user_id IN (:ids)\n").param("ids", ids).query(ProfileRow.class).list()
                .stream().collect(Collectors.toMap(ProfileRow::userId, ProfileRow::view));
    }

    void save(long id, Edit personal, String fieldPrefix) {
        byte[] image = personal.avatar() == null || personal.avatar().isEmpty()
                ? null : avatar(personal.avatar(), fieldPrefix + "avatar");
        jdbc.sql("MERGE INTO fm_user_profile profile\n"
                + "USING (SELECT CAST(:id AS NUMBER(19)) AS user_id FROM dual) incoming\n"
                + "ON (profile.user_id = incoming.user_id)\n"
                + "WHEN MATCHED THEN UPDATE SET profile.phone = :phone, profile.email = :email\n"
                + "WHEN NOT MATCHED THEN INSERT (user_id, phone, email) VALUES (incoming.user_id, :phone, :email)")
                .param("id", id).param("phone", stripToNull(personal.phone()))
                .param("email", stripToNull(personal.email())).update();
        if (personal.avatar() != null) {
            jdbc.sql("UPDATE fm_user_profile SET avatar = :avatar, avatar_version = avatar_version + 1 WHERE user_id = :id")
                    .param("id", id).blob("avatar", image).update();
        }
    }

    void saveUnit(long id, Long unitId) {
        if (unitId != null && !jdbc.sql("SELECT id FROM fm_fire_unit WHERE id = :id")
                .param("id", unitId).query(Long.class).optional().isPresent()) {
            throw invalidField("profile.unitId", "请选择有效的所属单位");
        }
        jdbc.sql("UPDATE fm_user_profile SET unit_id = :unit WHERE user_id = :id")
                .param("id", id).param("unit", unitId).update();
    }

    byte[] image(long id) {
        return jdbc.sql("SELECT avatar FROM fm_user_profile WHERE user_id = :id AND avatar IS NOT NULL")
                .param("id", id).query((rs, row) -> {
                    Blob blob = rs.getBlob("avatar");
                    try {
                        return blob.getBytes(1, (int) blob.length());
                    } finally {
                        blob.free();
                    }
                }).optional()
                .orElseThrow(() -> missing("头像不存在"));
    }

    private static byte[] avatar(String encoded, String field) {
        try {
            byte[] uploaded = Base64.getDecoder().decode(encoded);
            if (uploaded.length > MAX_UPLOAD_BYTES) throw invalidField(field, "头像大小不能超过 2 MB");
            try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(uploaded))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw invalidField(field, "请选择有效的 PNG 或 JPEG 图片");
                ImageReader reader = readers.next();
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
                    BufferedImage resized = new BufferedImage(Math.max(1, (int) Math.round(width * scale)),
                            Math.max(1, (int) Math.round(height * scale)), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = resized.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(reader.read(0), 0, 0, resized.getWidth(), resized.getHeight(), null);
                    } finally {
                        graphics.dispose();
                    }
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
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

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Profile {
        Long unitId;
        String unitName;
        String phone;
        String email;
        String avatarUrl;
    }
    @Value
    static class ProfileRow {
        long userId;
        Long unitId;
        String unitName;
        String phone;
        String email;
        Long avatarVersion;

        Profile view() {
            String url = avatarVersion == null ? null : "/api/users/" + userId + "/avatar?v=" + avatarVersion;
            return new Profile(unitId, unitName, phone, email, url);
        }
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Edit {
        @Size(max = 40, message = "联系电话最多 40 个字符") String phone;
        @Email(message = "请输入有效的电子邮箱") @Size(max = 254, message = "电子邮箱最多 254 个字符") String email;
        @Size(max = MAX_ENCODED_LENGTH, message = "头像大小不能超过 2 MB") String avatar;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Administration {
        @Positive(message = "请选择有效的所属单位") Long unitId;
        @NotNull @Valid Edit personal;
    }
}
