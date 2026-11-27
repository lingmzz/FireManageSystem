package com.firemanage.drill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

import com.firemanage.common.ApiSupport;
import com.firemanage.common.CreatedId;
import com.firemanage.common.JdbcClient;
import com.firemanage.identity.Permission;
import com.firemanage.organization.FireAccess;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.firemanage.common.ApiSupport.*;
import static org.apache.commons.lang3.StringUtils.strip;
import static org.apache.commons.lang3.StringUtils.stripToNull;

@RestController
@RequestMapping("/api/fire/drills")
class FireDrillController {
    private static final String SELECT = "SELECT d.id, d.unit_id, u.name AS unit_name, d.title, d.drilled_at, d.participant_count, d.summary, d.version,\n"
            + "       d.created_at FROM fm_fire_drill d JOIN fm_fire_unit u ON u.id = d.unit_id\n";
    private final JdbcClient jdbc;
    private final FireAccess fire;
    private final FireDrillFiles files;

    FireDrillController(JdbcClient jdbc, FireAccess fire, FireDrillFiles files) {
        this.jdbc = jdbc;
        this.fire = fire;
        this.files = files;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<View> list(Authentication auth, @RequestParam(required = false) Long unitId,
                    @RequestParam(defaultValue = "") String keyword,
                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DRILL_READ);
        ApiSupport.Paging paging = new Paging(page, size);
        JdbcClient.IdList units = new JdbcClient.IdList(scope.visibleIds(unitId));
        String pattern = "%" + strip(keyword) + "%";
        String filter = " WHERE " + units.matches("d.unit_id") + " AND LOWER(d.title) LIKE LOWER(:keyword)";
        long total = jdbc.sql("SELECT COUNT(*) FROM fm_fire_drill d" + filter)
                .params(units.parameters()).param("keyword", pattern).query(Long.class).single();
        List<Drill> rows = jdbc.page(SELECT + filter + " ORDER BY d.drilled_at DESC, d.id DESC", paging.offset(), paging.size())
                .params(units.parameters()).param("keyword", pattern)
                .query(Drill.class).list();
        return paging.result(rows.stream().map(row -> view(scope, row)).collect(Collectors.toList()), total);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public Detail detail(Authentication auth, @PathVariable long id) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DRILL_READ);
        Drill drill = drill(id);
        scope.requireUnit(drill.unitId());
        List<Attachment> attachments = jdbc.sql("SELECT id, kind, name, size_bytes, created_at FROM fm_fire_drill_file\n"
                + "WHERE drill_id = :id ORDER BY id\n").param("id", id).query(Attachment.class).list();
        return new Detail(view(scope, drill), attachments);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedId create(Authentication auth, @Valid @RequestBody Form form) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DRILL_WRITE);
        requireEditable(scope, form.unitId());
        long id = fields(jdbc.sql("INSERT INTO fm_fire_drill (id, unit_id, title, drilled_at, participant_count, summary, created_by)\n"
                + "VALUES (:newId, :unit, :title, :time, :participants, :summary, :actor)\n"), form).param("unit", form.unitId()).param("actor", scope.actor().id()).insert();
        return new CreatedId(id);
    }

    @PutMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void edit(Authentication auth, @PathVariable long id, @Valid @RequestBody Form form) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DRILL_WRITE);
        Locked current = lock(id);
        requireEditable(scope, current.unitId());
        if (current.unitId() != form.unitId()) throw invalidField("unitId", "演练所属单位不能通过编辑修改");
        if (form.version() == null || form.version() != current.version()) throw conflict("演练记录已更新");
        fields(jdbc.sql("UPDATE fm_fire_drill SET title = :title, drilled_at = :time, participant_count = :participants,\n"
                + "    summary = :summary, version = version + 1 WHERE id = :id\n"), form).param("id", id).update();
    }

    @PostMapping("/{id}/files")
    @Transactional(rollbackFor = IOException.class)
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedId upload(Authentication auth, @PathVariable long id,
                              @RequestParam Kind kind, @RequestParam("file") MultipartFile file) throws IOException {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DRILL_WRITE);
        requireEditable(scope, lock(id).unitId());
        FireDrillFiles.Saved saved = files.save(kind, file);
        long attachment = jdbc.sql("INSERT INTO fm_fire_drill_file (id, drill_id, kind, name, storage_key, content_type, size_bytes)\n"
                + "VALUES (:newId, :drill, :kind, :name, :key, :type, :size)\n")
                .param("drill", id).param("kind", kind.name()).param("name", saved.name())
                .param("key", saved.key()).param("type", saved.contentType()).param("size", saved.size()).insert();
        return new CreatedId(attachment);
    }

    @GetMapping("/{id}/files/{attachment}")
    ResponseEntity<Resource> download(Authentication auth, @PathVariable long id, @PathVariable long attachment) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DRILL_READ);
        scope.requireUnit(drill(id).unitId());
        Stored file = attachment(id, attachment);
        ContentDisposition.Builder disposition = file.kind() == Kind.PHOTO ? ContentDisposition.inline() : ContentDisposition.attachment();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .contentLength(file.sizeBytes()).body(files.load(file.storageKey()));
    }

    @DeleteMapping("/{id}/files/{attachment}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFile(Authentication auth, @PathVariable long id, @PathVariable long attachment) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DRILL_WRITE);
        requireEditable(scope, lock(id).unitId());
        Stored file = attachment(id, attachment);
        jdbc.sql("DELETE FROM fm_fire_drill_file WHERE id = :id").param("id", attachment).update();
        files.deleteAfterCommit(file.storageKey());
    }

    private Drill drill(long id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id").param("id", id).query(Drill.class)
                .optional().orElseThrow(() -> missing("应急演练不存在"));
    }

    private Locked lock(long id) {
        return jdbc.sql("SELECT unit_id, version FROM fm_fire_drill WHERE id = :id FOR UPDATE").param("id", id)
                .query(Locked.class).optional().orElseThrow(() -> missing("应急演练不存在"));
    }

    private Stored attachment(long id, long attachment) {
        return jdbc.sql("SELECT kind, name, storage_key, content_type, size_bytes FROM fm_fire_drill_file\n"
                + "WHERE drill_id = :drill AND id = :id\n").param("drill", id).param("id", attachment).query(Stored.class)
                .optional().orElseThrow(() -> missing("附件不存在"));
    }

    private static JdbcClient.StatementSpec fields(JdbcClient.StatementSpec stmt, Form form) {
        return stmt.param("title", strip(form.title())).param("time", form.drilledAt())
                .param("participants", form.participantCount()).clob("summary", stripToNull(form.summary()));
    }

    private static View view(FireAccess.Scope scope, Drill drill) {
        return new View(drill, scope.actor().has(Permission.FIRE_DRILL_WRITE)
                && canEdit(scope, drill.unitId()));
    }

    private static void requireEditable(FireAccess.Scope scope, long unit) {
        if (!canEdit(scope, unit)) throw forbidden();
    }

    private static boolean canEdit(FireAccess.Scope scope, long unit) {
        return scope.hasCoverage(unit, FireAccess.Kind.STATION) || scope.hasCoverage(unit, FireAccess.Kind.SEGMENT);
    }

    private FireAccess.Scope authorized(Authentication auth, Permission permission) {
        FireAccess.Scope scope = fire.scope(auth);
        scope.actor().require(permission);
        return scope;
    }

    enum Kind { SUMMARY, PHOTO }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Form {
        @NotNull @Positive Long unitId;
        @NotBlank @Size(max = 160) String title;
        @NotNull LocalDateTime drilledAt;
        @NotNull @Positive Integer participantCount;
        @Size(max = 15000) String summary;
        @Min(0) Integer version;
    }
    @Value
    static class Locked {
        long unitId;
        int version;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Drill {
        long id;
        long unitId;
        String unitName;
        String title;
        LocalDateTime drilledAt;
        int participantCount;
        String summary;
        int version;
        LocalDateTime createdAt;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class View {
        Drill drill;
        boolean editable;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Attachment {
        long id;
        Kind kind;
        String name;
        long sizeBytes;
        LocalDateTime createdAt;
    }
    @Value
    static class Stored {
        Kind kind;
        String name;
        String storageKey;
        String contentType;
        long sizeBytes;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Detail {
        View item;
        List<Attachment> attachments;
    }
}
