package com.firemanage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.firemanage.ApiSupport.bad;
import static com.firemanage.ApiSupport.conflict;
import static com.firemanage.ApiSupport.forbidden;
import static com.firemanage.ApiSupport.insert;
import static com.firemanage.ApiSupport.invalidField;
import static com.firemanage.ApiSupport.missing;

@RestController
@RequestMapping("/api/approval-flows/tasks")
class ApprovalFlowRuns {
    static final String WORKBENCH_ACTIONABLE = """
            EXISTS (
                SELECT 1 FROM fm_approval_route run
                JOIN fm_workflow_step step ON step.version_id = run.version_id AND step.step_key = run.step_key
                JOIN fm_user_role ur ON ur.role_id = step.role_id AND ur.user_id = :actor
                LEFT JOIN fm_approval_step_owner owner ON owner.approval_id = run.approval_id AND owner.step_key = run.step_key
                WHERE run.approval_id = a.id AND (NOT step.assignee_only OR owner.user_id = :actor)
            )
            """;
    private static final String CURRENT = """
            SELECT run.approval_id, run.version_id, v.flow_type, v.version_no, a.version AS record_version,
                   au.unit_id, run.step_key, step.step_name, step.role_name, r.code AS role_code, step.unit_scope,
                   COALESCE(step.assignee_only, FALSE) AS assignee_only, owner.user_id AS owner_id,
                   step.pass_label, step.pass_to, step.return_to, COALESCE(step.remark_required, FALSE) AS remark_required,
                   COALESCE(next_step.assignee_only, FALSE) AS needs_assignee, next_step.role_id AS next_role_id
            FROM fm_approval_route run
            JOIN fm_approval a ON a.id = run.approval_id
            JOIN fm_workflow_version v ON v.id = run.version_id
            LEFT JOIN fm_fire_approval_unit au ON au.approval_id = run.approval_id
            LEFT JOIN fm_workflow_step step ON step.version_id = run.version_id AND step.step_key = run.step_key
            LEFT JOIN fm_role r ON r.id = step.role_id
            LEFT JOIN fm_approval_step_owner owner ON owner.approval_id = run.approval_id AND owner.step_key = run.step_key
            LEFT JOIN fm_workflow_step next_step ON next_step.version_id = run.version_id AND next_step.step_key = step.pass_to
            WHERE run.approval_id IN (:ids)
            """;
    private static final String ELIGIBLE_ASSIGNEE = """
            u.enabled = TRUE AND EXISTS (
                SELECT 1 FROM fm_user_role chosen WHERE chosen.user_id = u.id AND chosen.role_id = :role
            ) AND EXISTS (
                SELECT 1 FROM fm_user_role ur JOIN fm_role r ON r.id = ur.role_id
                LEFT JOIN fm_role_permission p ON p.role_id = r.id AND p.permission_code = :reader
                WHERE ur.user_id = u.id AND (r.code = 'ADMIN' OR p.permission_code IS NOT NULL)
            )
            """;

    private final JdbcClient jdbc;
    private final AccessService access;
    private final FireAccess fire;

    ApprovalFlowRuns(JdbcClient jdbc, AccessService access, FireAccess fire) {
        this.jdbc = jdbc;
        this.access = access;
        this.fire = fire;
    }

    Start beginning(ApprovalFlow.Type type) {
        return jdbc.sql("""
                SELECT v.id AS version_id, v.start_key, s.step_name
                FROM fm_workflow_config c JOIN fm_workflow_version v ON v.id = c.active_version_id
                JOIN fm_workflow_step s ON s.version_id = v.id AND s.step_key = v.start_key
                WHERE c.flow_type = :type FOR SHARE
                """).param("type", type.name()).query(Start.class).optional().orElse(null);
    }

    void start(long id, long actor, Start start, String actionLabel) {
        jdbc.sql("""
                INSERT INTO fm_approval_route (approval_id, version_id, step_key) VALUES (:id, :version, :step)
                """).param("id", id).param("version", start.versionId()).param("step", start.startKey()).update();
        log(id, actor, "CREATE", actionLabel, null, "IN_WORKFLOW", null, start.stepName(), null);
    }

    Map<Long, Display> displays(List<Long> ids, AccessService.Actor actor, FireAccess.Scope scope) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.sql(CURRENT).param("ids", ids).query(Current.class).list().stream()
                .collect(Collectors.toMap(Current::approvalId, current -> current.display(actor, scope)));
    }

    @GetMapping("/{id}/assignees")
    @Transactional(readOnly = true)
    List<ApprovalController.Assignee> assignees(Authentication auth, @PathVariable long id,
                                                @RequestParam(defaultValue = "") String keyword) {
        if (keyword.length() > 80) throw bad("搜索词过长");
        var current = current(id);
        requireHandler(auth, current);
        if (!current.needsAssignee()) throw bad("下一个步骤由办理角色接收");
        return jdbc.sql("""
                SELECT u.id, u.username, u.display_name FROM fm_user u WHERE
                """ + ELIGIBLE_ASSIGNEE + """
                AND (u.display_name LIKE :keyword OR u.username LIKE :keyword)
                ORDER BY u.display_name, u.id LIMIT 100
                """).param("role", current.nextRoleId()).param("reader", current.flowType().reader.code)
                .param("keyword", "%" + keyword.strip() + "%").query(ApprovalController.Assignee.class).list();
    }

    @PostMapping("/{id}/actions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    void move(Authentication auth, @PathVariable long id, @Valid @RequestBody Process request) {
        jdbc.sql("SELECT id FROM fm_approval WHERE id = :id FOR UPDATE")
                .param("id", id).query(Long.class).optional().orElseThrow(() -> missing("审批单不存在"));
        var current = current(id);
        var actor = requireHandler(auth, current);
        if (current.recordVersion() != request.version()) {
            throw conflict("审批已被更新，请刷新后重试");
        }
        boolean returning = request.action() == Decision.RETURN;
        String remark = FireDeviceController.clean(request.remark());
        if (returning && current.returnTo() == null) throw invalidField("action", "这个步骤没有设置退回去向");
        if ((returning || current.remarkRequired()) && remark == null) {
            throw invalidField("remark", returning ? "请填写退回原因" : "请填写办理说明");
        }
        String stepKey = returning ? current.returnTo() : current.passTo();
        var target = stepKey == null ? null : target(current.versionId(), stepKey);
        Long nextAssignee = null;
        if (!returning && current.needsAssignee()) {
            nextAssignee = request.assigneeId();
            if (nextAssignee == null || !eligible(nextAssignee, current)) {
                throw invalidField("assigneeId", "请选择一位启用且属于下一步骤办理角色的人员");
            }
            jdbc.sql("""
                    INSERT INTO fm_approval_step_owner (approval_id, step_key, user_id) VALUES (:id, :step, :user)
                    ON DUPLICATE KEY UPDATE user_id = :user
                    """).param("id", id).param("step", stepKey).param("user", nextAssignee).update();
        } else if (returning && target != null && target.assigneeOnly()) {
            nextAssignee = jdbc.sql("""
                    SELECT user_id FROM fm_approval_step_owner WHERE approval_id = :id AND step_key = :step
                    """).param("id", id).param("step", stepKey).query(Long.class).optional()
                    .orElseThrow(() -> conflict("该步骤原先指定的办理人无法确定"));
        }

        String nextStatus = stepKey == null ? "CLOSED" : "IN_WORKFLOW";
        jdbc.sql("UPDATE fm_approval_route SET step_key = :step WHERE approval_id = :id")
                .param("step", stepKey).param("id", id).update();
        jdbc.sql("""
                UPDATE fm_approval SET status = :status, assignee_id = :user,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = :id
                """).param("status", nextStatus).param("user", nextAssignee).param("id", id).update();
        String nextLabel = target == null ? closeLabel(current.flowType()) : target.stepName();
        log(id, actor.id(), returning ? "FLOW_RETURN" : "FLOW_PASS", returning ? "退回" : current.passLabel(),
                "IN_WORKFLOW", nextStatus, current.stepName(), nextLabel, remark);
    }

    private static String closeLabel(ApprovalFlow.Type type) {
        return type == ApprovalFlow.Type.WORKBENCH ? "已销项" : "已销号";
    }

    private Current current(long id) {
        return jdbc.sql(CURRENT).param("ids", List.of(id)).query(Current.class).optional()
                .orElseThrow(() -> missing("流程单据不存在"));
    }

    private AccessService.Actor requireHandler(Authentication auth, Current current) {
        var actor = access.actor(auth);
        actor.require(current.flowType().reader);
        var scope = current.unitId() == null ? null : fire.scope(actor);
        if (scope != null && !scope.canSee(current.unitId())) throw forbidden();
        if (current.stepKey() == null) throw conflict("该流程已办结");
        if (!current.canAct(actor, scope)) throw forbidden();
        return actor;
    }

    private boolean eligible(long id, Current current) {
        return jdbc.sql("SELECT u.id FROM fm_user u WHERE u.id = :id AND " + ELIGIBLE_ASSIGNEE)
                .param("id", id).param("role", current.nextRoleId()).param("reader", current.flowType().reader.code)
                .query(Long.class).optional().isPresent();
    }

    private Target target(long version, String key) {
        return jdbc.sql("SELECT step_name, assignee_only FROM fm_workflow_step WHERE version_id = :version AND step_key = :key")
                .param("version", version).param("key", key).query(Target.class).single();
    }

    private void log(long id, long actor, String action, String label, String fromStatus, String toStatus,
                     String fromStep, String toStep, String remark) {
        long event = insert(jdbc.sql("""
                INSERT INTO fm_approval_event (approval_id, actor_id, action, from_status, to_status, remark)
                VALUES (:id, :actor, :action, :before, :after, :remark)
                """).param("id", id).param("actor", actor).param("action", action)
                .param("before", fromStatus).param("after", toStatus).param("remark", remark));
        jdbc.sql("""
                INSERT INTO fm_approval_flow_event (event_id, action_label, from_step, to_step)
                VALUES (:id, :label, :before, :after)
                """).param("id", event).param("label", label).param("before", fromStep).param("after", toStep).update();
    }

    enum Decision { PASS, RETURN }
    record Process(@NotNull Decision action, @NotNull @Min(0) Integer version,
                   @Positive Long assigneeId, @Size(max = 3000) String remark) {}
    record Start(long versionId, String startKey, String stepName) {}
    record Target(String stepName, boolean assigneeOnly) {}
    record Display(int version, String stepName, String roleName, boolean canAct, boolean canReturn,
                   boolean needsAssignee, boolean remarkRequired, String passLabel) {}
    record Current(long approvalId, long versionId, ApprovalFlow.Type flowType, int versionNo, int recordVersion,
                   Long unitId, String stepKey, String stepName, String roleName, String roleCode, ApprovalFlow.UnitScope unitScope,
                   boolean assigneeOnly, Long ownerId, String passLabel, String passTo, String returnTo, boolean remarkRequired,
                   boolean needsAssignee, Long nextRoleId) {
        boolean canAct(AccessService.Actor actor, FireAccess.Scope scope) {
            return stepKey != null && roleCode != null && actor.roles().contains(roleCode)
                    && unitScope.allows(scope, unitId) && (!assigneeOnly || Objects.equals(actor.id(), ownerId));
        }

        Display display(AccessService.Actor actor, FireAccess.Scope scope) {
            return new Display(versionNo, stepName, roleName, canAct(actor, scope),
                    returnTo != null, needsAssignee, remarkRequired, passLabel);
        }
    }
}
