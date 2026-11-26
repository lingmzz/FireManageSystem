package com.firemanage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
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
import static com.firemanage.ApiSupport.missing;
import static com.firemanage.ApprovalWorkflow.Status.PENDING_ASSIGNMENT;

@RestController
@RequestMapping("/api/approvals")
class ApprovalController {
    private static final String FIELDS = """
            SELECT a.id, a.title, a.location, a.description, a.reporter_id,
                   reporter.display_name AS reporter_name, a.assignee_id, assigned.display_name AS assignee_name,
                   a.status, a.version, a.created_at, a.updated_at
            FROM fm_approval a
            JOIN fm_user reporter ON reporter.id = a.reporter_id
            LEFT JOIN fm_user assigned ON assigned.id = a.assignee_id
            """;
    private static final String SCOPE = """
             NOT EXISTS (SELECT 1 FROM fm_fire_approval_unit fu WHERE fu.approval_id = a.id)
             AND (:viewAll = TRUE OR a.reporter_id = :actor OR a.assignee_id = :actor
                    OR (:canAssign = TRUE AND a.status = 'PENDING_ASSIGNMENT')
                    OR (:canReview = TRUE AND a.status = 'PENDING_REVIEW')
                    OR %s
                    OR EXISTS (SELECT 1 FROM fm_approval_event e WHERE e.approval_id = a.id AND e.actor_id = :actor))
            """.formatted(ApprovalFlowRuns.WORKBENCH_ACTIONABLE);
    private static final String TODO = """
            AND ((:canAssign = TRUE AND a.status = 'PENDING_ASSIGNMENT')
                OR (:canRectify = TRUE AND a.status = 'PENDING_RECTIFICATION' AND a.assignee_id = :actor)
                OR (:canReview = TRUE AND a.status = 'PENDING_REVIEW')
                OR %s)
            """.formatted(ApprovalFlowRuns.WORKBENCH_ACTIONABLE);
    private static final String ASSIGNEE = """
            u.enabled = TRUE AND EXISTS (
                SELECT 1 FROM fm_user_role ur
                JOIN fm_role r ON r.id = ur.role_id
                LEFT JOIN fm_role_permission rp ON rp.role_id = r.id AND rp.permission_code = :rectification
                WHERE ur.user_id = u.id AND (r.code = 'ADMIN' OR rp.permission_code IS NOT NULL)
            )
            """;
    private final JdbcClient jdbc;
    private final AccessService access;
    private final ApprovalFlowRuns flows;

    ApprovalController(JdbcClient jdbc, AccessService access, ApprovalFlowRuns flows) {
        this.jdbc = jdbc;
        this.access = access;
        this.flows = flows;
    }

    @GetMapping
    @Transactional(readOnly = true)
    ApiSupport.Page<ApprovalView> list(Authentication authentication,
                                       @RequestParam(defaultValue = "all") String view,
                                       @RequestParam(required = false) ApprovalWorkflow.Status status,
                                       @RequestParam(defaultValue = "") String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        var actor = reader(authentication);
        var paging = new ApiSupport.Paging(page, size);
        if (keyword.length() > 120) {
            throw bad("搜索词过长");
        }
        String mode = switch (view) {
            case "all" -> "";
            case "mine" -> " AND a.reporter_id = :actor";
            case "todo" -> TODO;
            default -> throw bad("未知的审批列表类型");
        };
        String filter = " WHERE " + SCOPE + mode;
        if (status != null) {
            filter += " AND a.status = :status";
        }
        if (!keyword.isBlank()) {
            filter += " AND (a.title LIKE :pattern OR a.location LIKE :pattern)";
        }
        long total = listParams(jdbc.sql("SELECT COUNT(*) FROM fm_approval a" + filter), actor, view, status, keyword)
                .query(Long.class).single();
        var rows = listParams(jdbc.sql(FIELDS + filter + " ORDER BY a.id DESC LIMIT :size OFFSET :offset"),
                actor, view, status, keyword).param("size", paging.size()).param("offset", paging.offset())
                .query(Approval.class).list();
        var workflows = flows.displays(rows.stream().map(Approval::id).toList(), actor, null);
        return paging.result(rows.stream().map(row ->
                new ApprovalView(row, actions(actor, row.status(), row.assigneeId()), workflows.get(row.id())))
                .toList(), total);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    Details detail(Authentication authentication, @PathVariable long id) {
        var actor = reader(authentication);
        var approval = scope(jdbc.sql(FIELDS + " WHERE a.id = :id AND " + SCOPE), actor)
                .param("id", id).query(Approval.class).optional().orElseThrow(() -> missing("隐患单不存在或无权查看"));
        var events = jdbc.sql("""
                SELECT e.action, e.from_status, e.to_status, e.remark, e.created_at, u.display_name AS actor_name,
                       flow.action_label, flow.from_step, flow.to_step
                FROM fm_approval_event e JOIN fm_user u ON u.id = e.actor_id
                LEFT JOIN fm_approval_flow_event flow ON flow.event_id = e.id
                WHERE e.approval_id = :id ORDER BY e.id
                """).param("id", id).query(Event.class).list();
        var workflow = flows.displays(List.of(id), actor, null).get(id);
        return new Details(approval, actions(actor, approval.status(), approval.assigneeId()), events, workflow);
    }

    @GetMapping("/assignees")
    List<Assignee> assignees(Authentication authentication, @RequestParam(defaultValue = "") String keyword) {
        var actor = reader(authentication);
        actor.require(Permission.APPROVAL_ASSIGN);
        if (keyword.length() > 80) {
            throw bad("搜索词过长");
        }
        String search = keyword.strip();
        return jdbc.sql("SELECT id, username, display_name FROM fm_user u WHERE " + ASSIGNEE
                        + " AND (display_name LIKE :pattern OR username LIKE :pattern) ORDER BY display_name, id LIMIT 100")
                .param("rectification", Permission.APPROVAL_RECTIFY.code)
                .param("pattern", "%" + search + "%").query(Assignee.class).list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AdminController.Id create(Authentication authentication, @Valid @RequestBody Create request) {
        var actor = reader(authentication);
        actor.require(Permission.APPROVAL_CREATE);
        var start = flows.beginning(ApprovalFlow.Type.WORKBENCH);
        var status = start == null ? PENDING_ASSIGNMENT : ApprovalWorkflow.Status.IN_WORKFLOW;
        long id = insert(jdbc.sql("""
                INSERT INTO fm_approval (title, location, description, reporter_id, status)
                VALUES (:title, :location, :description, :reporter, :status)
                """).param("title", request.title().strip()).param("location", request.location().strip())
                .param("description", request.description().strip()).param("reporter", actor.id())
                .param("status", status.name()));
        if (start == null) log(id, actor.id(), "CREATE", null, status, null);
        else flows.start(id, actor.id(), start, "上报隐患");
        return new AdminController.Id(id);
    }

    @PostMapping("/{id}/actions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void process(Authentication authentication, @PathVariable long id, @Valid @RequestBody Process request) {
        var actor = reader(authentication);
        actor.require(required(request.action()));
        var current = scope(jdbc.sql("""
                SELECT a.status, a.assignee_id, a.version FROM fm_approval a
                WHERE a.id = :id AND
                """ + SCOPE + " FOR UPDATE"), actor).param("id", id).query(LockedApproval.class).optional()
                .orElseThrow(() -> missing("隐患单不存在或无权查看"));
        if (current.version() != request.version()) {
            throw conflict("审批已被更新，请刷新后重试");
        }
        ApprovalWorkflow.Status next;
        try {
            next = ApprovalWorkflow.next(current.status(), request.action());
        } catch (IllegalStateException failure) {
            throw conflict(failure.getMessage());
        }
        Long assignee = current.assigneeId();
        String remark = request.remark() == null ? "" : request.remark().strip();
        switch (request.action()) {
            case ASSIGN -> {
                assignee = request.assigneeId();
                if (assignee == null || !canRectify(assignee)) {
                    throw ApiSupport.invalidField("assigneeId", "请选择一位启用且具有整改权限的办理人");
                }
            }
            case SUBMIT_RECTIFICATION -> {
                if (!Objects.equals(assignee, actor.id())) {
                    throw forbidden();
                }
                if (remark.isBlank()) {
                    throw ApiSupport.invalidField("remark", "请填写整改说明");
                }
            }
            case RETURN -> {
                if (remark.isBlank()) {
                    throw ApiSupport.invalidField("remark", "请填写退回原因");
                }
            }
            case APPROVE -> { }
        }
        int changed = jdbc.sql("""
                UPDATE fm_approval SET status = :status, assignee_id = :assignee,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND version = :currentVersion
                """).param("status", next.name()).param("assignee", assignee).param("id", id)
                .param("currentVersion", current.version()).update();
        if (changed != 1) {
            throw conflict("审批已被更新，请刷新后重试");
        }
        log(id, actor.id(), request.action().name(), current.status(), next, remark.isEmpty() ? null : remark);
    }

    private AccessService.Actor reader(Authentication authentication) {
        var actor = access.actor(authentication);
        actor.require(Permission.APPROVAL_READ);
        return actor;
    }

    private boolean canRectify(long user) {
        return jdbc.sql("SELECT COUNT(*) FROM fm_user u WHERE u.id = :user AND " + ASSIGNEE)
                .param("user", user).param("rectification", Permission.APPROVAL_RECTIFY.code)
                .query(Long.class).single() == 1;
    }

    private static JdbcClient.StatementSpec listParams(JdbcClient.StatementSpec sql, AccessService.Actor actor,
                                                        String view, ApprovalWorkflow.Status status, String keyword) {
        scope(sql, actor);
        if ("todo".equals(view)) {
            sql.param("canRectify", actor.has(Permission.APPROVAL_RECTIFY));
        }
        if (status != null) {
            sql.param("status", status.name());
        }
        if (!keyword.isBlank()) {
            sql.param("pattern", "%" + keyword.strip() + "%");
        }
        return sql;
    }

    private static JdbcClient.StatementSpec scope(JdbcClient.StatementSpec sql, AccessService.Actor actor) {
        return sql.param("actor", actor.id()).param("viewAll", actor.has(Permission.APPROVAL_ALL))
                .param("canAssign", actor.has(Permission.APPROVAL_ASSIGN))
                .param("canReview", actor.has(Permission.APPROVAL_REVIEW));
    }

    private static List<ApprovalWorkflow.Action> actions(AccessService.Actor actor, ApprovalWorkflow.Status status, Long assignee) {
        var result = new ArrayList<ApprovalWorkflow.Action>();
        switch (status) {
            case PENDING_ASSIGNMENT -> {
                if (actor.has(Permission.APPROVAL_ASSIGN)) {
                    result.add(ApprovalWorkflow.Action.ASSIGN);
                }
            }
            case PENDING_RECTIFICATION -> {
                if (actor.has(Permission.APPROVAL_RECTIFY) && Objects.equals(actor.id(), assignee)) {
                    result.add(ApprovalWorkflow.Action.SUBMIT_RECTIFICATION);
                }
            }
            case PENDING_REVIEW -> {
                if (actor.has(Permission.APPROVAL_REVIEW)) {
                    result.add(ApprovalWorkflow.Action.APPROVE);
                    result.add(ApprovalWorkflow.Action.RETURN);
                }
            }
            case IN_WORKFLOW, CLOSED -> { }
        }
        return result;
    }

    private static Permission required(ApprovalWorkflow.Action action) {
        return switch (action) {
            case ASSIGN -> Permission.APPROVAL_ASSIGN;
            case SUBMIT_RECTIFICATION -> Permission.APPROVAL_RECTIFY;
            case APPROVE, RETURN -> Permission.APPROVAL_REVIEW;
        };
    }

    private void log(long approval, long actor, String action, ApprovalWorkflow.Status from,
                     ApprovalWorkflow.Status to, String remark) {
        jdbc.sql("""
                INSERT INTO fm_approval_event (approval_id, actor_id, action, from_status, to_status, remark)
                VALUES (:approval, :actor, :action, :previous, :next, :remark)
                """).param("approval", approval).param("actor", actor).param("action", action)
                .param("previous", from == null ? null : from.name()).param("next", to.name()).param("remark", remark).update();
    }

    record Approval(long id, String title, String location, String description, long reporterId, String reporterName,
                    Long assigneeId, String assigneeName, ApprovalWorkflow.Status status, int version,
                    LocalDateTime createdAt, LocalDateTime updatedAt) {}
    record ApprovalView(Approval approval, List<ApprovalWorkflow.Action> actions, ApprovalFlowRuns.Display flow) {}
    record Details(Approval approval, List<ApprovalWorkflow.Action> actions, List<Event> history, ApprovalFlowRuns.Display flow) {}
    record LockedApproval(ApprovalWorkflow.Status status, Long assigneeId, int version) {}
    record Event(String action, String fromStatus, String toStatus, String remark, LocalDateTime createdAt,
                 String actorName, String actionLabel, String fromStep, String toStep) {}
    record Assignee(long id, String username, String displayName) {}

    record Create(@NotBlank(message = "请输入隐患标题") @Size(max = 120) String title,
                  @NotBlank(message = "请输入场所或位置") @Size(max = 120) String location,
                  @NotBlank(message = "请描述隐患") @Size(max = 3000) String description) {}
    record Process(@NotNull ApprovalWorkflow.Action action, @NotNull @Min(0) Integer version,
                   @Positive Long assigneeId, @Size(max = 3000) String remark) {}
}
