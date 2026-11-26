package com.firemanage;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.firemanage.ApiSupport.conflict;
import static com.firemanage.ApiSupport.insert;
import static com.firemanage.ApiSupport.invalidField;

@RestController
@RequestMapping("/api/approval-flows")
class ApprovalFlowSettings {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AccessService access;

    ApprovalFlowSettings(JdbcClient jdbc, ObjectMapper json, AccessService access) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
    }

    @GetMapping("/{type}")
    @Transactional(readOnly = true)
    Settings show(Authentication auth, @PathVariable ApprovalFlow.Type type) {
        manager(auth);
        return settings(type);
    }

    @PutMapping("/{type}/draft")
    @Transactional
    Settings save(Authentication auth, @PathVariable ApprovalFlow.Type type, @Valid @RequestBody Edit edit) {
        manager(auth);
        lock(type, edit.revision());
        jdbc.sql("""
                UPDATE fm_workflow_config SET draft = :draft, revision = revision + 1 WHERE flow_type = :type
                """).param("draft", encode(edit.draft())).param("type", type.name()).update();
        return settings(type);
    }

    @PostMapping("/{type}/publish")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    Settings publish(Authentication auth, @PathVariable ApprovalFlow.Type type, @Valid @RequestBody Edit edit) {
        var actor = manager(auth);
        var config = lock(type, edit.revision());
        ApprovalFlow.validate(type, edit.draft());
        var ids = edit.draft().steps().stream().map(ApprovalFlow.Step::roleId).distinct().sorted().toList();
        jdbc.sql("SELECT id FROM fm_role WHERE id IN (:ids) ORDER BY id FOR SHARE")
                .param("ids", ids).query(Long.class).list();
        var roles = roles(type).stream().collect(Collectors.toMap(Role::id, Role::name));
        for (var step : edit.draft().steps()) {
            if (!roles.containsKey(step.roleId())) {
                throw invalidField(step.field("roleId"), "请选择拥有此入口查看权限的角色");
            }
        }

        int number = config.lastVersion() + 1;
        long version = insert(jdbc.sql("""
                INSERT INTO fm_workflow_version (flow_type, version_no, start_key, published_by)
                VALUES (:type, :number, :start, :publisher)
                """).param("type", type.name()).param("number", number)
                .param("start", edit.draft().startKey()).param("publisher", actor.id()));
        saveSteps(version, edit.draft().steps(), roles);
        jdbc.sql("""
                UPDATE fm_workflow_config SET active_version_id = :version, last_version = :number,
                    draft = :draft, revision = revision + 1 WHERE flow_type = :type
                """).param("version", version).param("number", number).param("draft", encode(edit.draft()))
                .param("type", type.name()).update();
        return settings(type);
    }

    @PutMapping("/{type}/built-in")
    @Transactional
    Settings useBuiltIn(Authentication auth, @PathVariable ApprovalFlow.Type type, @Valid @RequestBody Revision request) {
        manager(auth);
        lock(type, request.revision());
        jdbc.sql("""
                UPDATE fm_workflow_config SET active_version_id = NULL, revision = revision + 1 WHERE flow_type = :type
                """).param("type", type.name()).update();
        return settings(type);
    }

    private AccessService.Actor manager(Authentication auth) {
        var actor = access.actor(auth);
        actor.require(Permission.WORKFLOWS);
        return actor;
    }

    private Config lock(ApprovalFlow.Type type, int revision) {
        var config = config(type, true);
        if (config.revision() != revision) {
            throw conflict("这套流程已被其他人更新。请重新加载后再保存");
        }
        return config;
    }

    private Config config(ApprovalFlow.Type type, boolean lock) {
        String query = "SELECT revision, last_version, active_version_id, draft FROM fm_workflow_config WHERE flow_type = :type";
        return jdbc.sql(lock ? query + " FOR UPDATE" : query).param("type", type.name()).query(Config.class).single();
    }

    private Settings settings(ApprovalFlow.Type type) {
        var config = config(type, false);
        var roles = roles(type);
        var byCode = roles.stream().collect(Collectors.toMap(Role::code, Role::id));
        var example = ApprovalFlow.example(type, byCode);
        var types = Arrays.stream(ApprovalFlow.Type.values()).map(value -> new Choice(value.name(), value.label)).toList();
        var scopes = type.scopes.stream().map(value -> new Choice(value.name(), value.label)).toList();
        return new Settings(config.revision(), types, scopes, roles, example,
                config.draft() == null ? example : decode(config.draft()), published(config.activeVersionId()));
    }

    private List<Role> roles(ApprovalFlow.Type type) {
        return jdbc.sql("""
                SELECT r.id, r.code, r.name FROM fm_role r
                WHERE r.code = 'ADMIN' OR EXISTS (
                    SELECT 1 FROM fm_role_permission p WHERE p.role_id = r.id AND p.permission_code = :reader
                )
                ORDER BY r.id
                """).param("reader", type.reader.code).query(Role.class).list();
    }

    private Published published(Long id) {
        if (id == null) return null;
        var header = jdbc.sql("""
                SELECT v.version_no, v.start_key, v.published_at, u.display_name AS publisher
                FROM fm_workflow_version v JOIN fm_user u ON u.id = v.published_by WHERE v.id = :id
                """).param("id", id).query(Header.class).single();
        var steps = jdbc.sql("""
                SELECT step_key AS `key`, step_name AS name, role_id, unit_scope, assignee_only,
                       pass_label, remark_required, pass_to, return_to
                FROM fm_workflow_step WHERE version_id = :id ORDER BY display_order
                """).param("id", id).query(ApprovalFlow.Step.class).list();
        return new Published(header.versionNo(), header.publishedAt(), header.publisher(),
                new ApprovalFlow.Draft(header.startKey(), steps));
    }

    private void saveSteps(long version, List<ApprovalFlow.Step> steps, Map<Long, String> roles) {
        for (int index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            jdbc.sql("""
                    INSERT INTO fm_workflow_step (version_id, step_key, display_order, step_name, role_id, role_name,
                        unit_scope, assignee_only, pass_label, remark_required, pass_to, return_to)
                    VALUES (:version, :key, :position, :name, :role, :roleName,
                        :scope, :assignee, :label, :remark, :pass, :back)
                    """).param("version", version).param("key", step.key()).param("position", index)
                    .param("name", step.name().strip()).param("role", step.roleId()).param("roleName", roles.get(step.roleId()))
                    .param("scope", step.unitScope().name()).param("assignee", step.assigneeOnly()).param("label", step.actionLabel())
                    .param("remark", step.remarkRequired()).param("pass", step.passTo()).param("back", step.returnTo()).update();
        }
    }

    private ApprovalFlow.Draft decode(String value) {
        try {
            return json.readValue(value, ApprovalFlow.Draft.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("已保存的流程草稿无法读取", failure);
        }
    }

    private String encode(ApprovalFlow.Draft value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("流程草稿无法保存", failure);
        }
    }

    record Config(int revision, int lastVersion, Long activeVersionId, String draft) {}
    record Header(int versionNo, String startKey, LocalDateTime publishedAt, String publisher) {}
    record Role(long id, String code, String name) {}
    record Choice(String value, String label) {}
    record Published(int version, LocalDateTime at, String by, ApprovalFlow.Draft definition) {}
    record Settings(int revision, List<Choice> types, List<Choice> scopes, List<Role> roles,
                    ApprovalFlow.Draft example, ApprovalFlow.Draft draft, Published published) {}
    record Edit(@NotNull @Min(0) Integer revision, @Valid @NotNull ApprovalFlow.Draft draft) {}
    record Revision(@NotNull @Min(0) Integer revision) {}
}
