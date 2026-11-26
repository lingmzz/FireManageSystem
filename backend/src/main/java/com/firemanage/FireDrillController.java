package com.firemanage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.firemanage.ApiSupport.*;
import static com.firemanage.FireDeviceController.clean;

@RestController
@RequestMapping("/api/fire/drills")
class FireDrillController {
    private static final String SELECT = """
            SELECT d.id, d.unit_id, u.name AS unit_name, d.title, d.drilled_at, d.participant_count, d.summary, d.version,
                   d.created_at FROM fm_fire_drill d JOIN fm_fire_unit u ON u.id = d.unit_id
            """;
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
    Page<View> list(Authentication auth, @RequestParam(required = false) Long unitId,
                    @RequestParam(defaultValue = "") String keyword,
                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
        var scope = authorized(auth, Permission.FIRE_DRILL_READ);
        var paging = new Paging(page, size);
        var units = scope.visibleIds(unitId);
        var pattern = "%" + keyword.strip() + "%";
        String filter = " WHERE d.unit_id IN (:units) AND d.title LIKE :keyword";
        long total = jdbc.sql("SELECT COUNT(*) FROM fm_fire_drill d" + filter)
                .param("units", units).param("keyword", pattern).query(Long.class).single();
        var rows = jdbc.sql(SELECT + filter + " ORDER BY d.drilled_at DESC, d.id DESC LIMIT :limit OFFSET :offset")
                .param("units", units).param("keyword", pattern).param("limit", size).param("offset", paging.offset())
                .query(Drill.class).list();
        return paging.result(rows.stream().map(row -> view(scope, row)).toList(), total);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    Detail detail(Authentication auth, @PathVariable long id) {
        var scope = authorized(auth, Permission.FIRE_DRILL_READ);
        var drill = drill(id);
        scope.requireUnit(drill.unitId());
        var attachments = jdbc.sql("""
                SELECT id, kind, name, size_bytes, created_at FROM fm_fire_drill_file
                WHERE drill_id = :id ORDER BY id
                """).param("id", id).query(Attachment.class).list();
        return new Detail(view(scope, drill), attachments);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    AdminController.Id create(Authentication auth, @Valid @RequestBody Form form) {
        var scope = authorized(auth, Permission.FIRE_DRILL_WRITE);
        requireEditable(scope, form.unitId());
        long id = insert(fields(jdbc.sql("""
                INSERT INTO fm_fire_drill (unit_id, title, drilled_at, participant_count, summary, created_by)
                VALUES (:unit, :title, :time, :participants, :summary, :actor)
                """), form).param("unit", form.unitId()).param("actor", scope.actor().id()));
        return new AdminController.Id(id);
    }

    @PutMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void edit(Authentication auth, @PathVariable long id, @Valid @RequestBody Form form) {
        var scope = authorized(auth, Permission.FIRE_DRILL_WRITE);
        var current = lock(id);
        requireEditable(scope, current.unitId());
        if (current.unitId() != form.unitId()) throw invalidField("unitId", "演练所属单位不能通过编辑修改");
        if (form.version() == null || form.version() != current.version()) throw conflict("演练记录已更新");
        fields(jdbc.sql("""
                UPDATE fm_fire_drill SET title = :title, drilled_at = :time, participant_count = :participants,
                    summary = :summary, version = version + 1 WHERE id = :id
                """), form).param("id", id).update();
    }

    @PostMapping("/{id}/files")
    @Transactional(rollbackFor = IOException.class)
    @ResponseStatus(HttpStatus.CREATED)
    AdminController.Id upload(Authentication auth, @PathVariable long id,
                              @RequestParam Kind kind, @RequestParam("file") MultipartFile file) throws IOException {
        var scope = authorized(auth, Permission.FIRE_DRILL_WRITE);
        requireEditable(scope, lock(id).unitId());
        var saved = files.save(kind, file);
        long attachment = insert(jdbc.sql("""
                INSERT INTO fm_fire_drill_file (drill_id, kind, name, storage_key, content_type, size_bytes)
                VALUES (:drill, :kind, :name, :key, :type, :size)
                """).param("drill", id).param("kind", kind.name()).param("name", saved.name())
                .param("key", saved.key()).param("type", saved.contentType()).param("size", saved.size()));
        return new AdminController.Id(attachment);
    }

    @GetMapping("/{id}/files/{attachment}")
    ResponseEntity<Resource> download(Authentication auth, @PathVariable long id, @PathVariable long attachment) {
        var scope = authorized(auth, Permission.FIRE_DRILL_READ);
        scope.requireUnit(drill(id).unitId());
        var file = attachment(id, attachment);
        var disposition = file.kind() == Kind.PHOTO ? ContentDisposition.inline() : ContentDisposition.attachment();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .contentLength(file.sizeBytes()).body(files.load(file.storageKey()));
    }

    @DeleteMapping("/{id}/files/{attachment}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeFile(Authentication auth, @PathVariable long id, @PathVariable long attachment) {
        var scope = authorized(auth, Permission.FIRE_DRILL_WRITE);
        requireEditable(scope, lock(id).unitId());
        var file = attachment(id, attachment);
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
        return jdbc.sql("""
                SELECT kind, name, storage_key, content_type, size_bytes FROM fm_fire_drill_file
                WHERE drill_id = :drill AND id = :id
                """).param("drill", id).param("id", attachment).query(Stored.class)
                .optional().orElseThrow(() -> missing("附件不存在"));
    }

    private static JdbcClient.StatementSpec fields(JdbcClient.StatementSpec stmt, Form form) {
        return stmt.param("title", form.title().strip()).param("time", form.drilledAt())
                .param("participants", form.participantCount()).param("summary", clean(form.summary()));
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
        var scope = fire.scope(auth);
        scope.actor().require(permission);
        return scope;
    }

    enum Kind { SUMMARY, PHOTO }
    record Form(@NotNull @Positive Long unitId, @NotBlank @Size(max = 160) String title,
                @NotNull LocalDateTime drilledAt, @NotNull @Positive Integer participantCount,
                @Size(max = 15000) String summary, @Min(0) Integer version) {}
    record Locked(long unitId, int version) {}
    record Drill(long id, long unitId, String unitName, String title, LocalDateTime drilledAt, int participantCount,
                 String summary, int version, LocalDateTime createdAt) {}
    record View(Drill drill, boolean editable) {}
    record Attachment(long id, Kind kind, String name, long sizeBytes, LocalDateTime createdAt) {}
    record Stored(Kind kind, String name, String storageKey, String contentType, long sizeBytes) {}
    record Detail(View item, List<Attachment> attachments) {}
}
