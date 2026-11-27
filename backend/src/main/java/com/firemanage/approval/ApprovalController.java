package com.firemanage.approval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.firemanage.identity.AccessService;
import com.firemanage.identity.Permission;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;
import org.springframework.http.HttpStatus;
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

import static com.firemanage.approval.ApprovalWorkflow.Status.PENDING_ASSIGNMENT;
import static com.firemanage.common.ApiSupport.bad;
import static com.firemanage.common.ApiSupport.conflict;
import static com.firemanage.common.ApiSupport.forbidden;
import static com.firemanage.common.ApiSupport.missing;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.strip;

@RestController
@RequestMapping("/api/approvals")
class ApprovalController {
    private static final String FIELDS = "SELECT a.id, a.title, a.location, a.description, a.reporter_id,\n"
            + "       reporter.display_name AS reporter_name, a.assignee_id, assigned.display_name AS assignee_name,\n"
            + "       a.status, a.version, a.created_at, a.updated_at\n"
            + "FROM fm_approval a\n"
            + "JOIN fm_user reporter ON reporter.id = a.reporter_id\n"
            + "LEFT JOIN fm_user assigned ON assigned.id = a.assignee_id\n";
    private static final String SCOPE = " NOT EXISTS (SELECT 1 FROM fm_fire_approval_unit fu WHERE fu.approval_id = a.id)\n"
            + " AND (:viewAll = 1 OR a.reporter_id = :actor OR a.assignee_id = :actor\n"
            + "        OR (:canAssign = 1 AND a.status = 'PENDING_ASSIGNMENT')\n"
            + "        OR (:canReview = 1 AND a.status = 'PENDING_REVIEW')\n"
            + "        OR " + ApprovalFlowRuns.WORKBENCH_ACTIONABLE
            + "        OR EXISTS (SELECT 1 FROM fm_approval_event e WHERE e.approval_id = a.id AND e.actor_id = :actor))\n";
    private static final String TODO = "AND ((:canAssign = 1 AND a.status = 'PENDING_ASSIGNMENT')\n"
            + "    OR (:canRectify = 1 AND a.status = 'PENDING_RECTIFICATION' AND a.assignee_id = :actor)\n"
            + "    OR (:canReview = 1 AND a.status = 'PENDING_REVIEW')\n"
            + "    OR " + ApprovalFlowRuns.WORKBENCH_ACTIONABLE + ")\n";
    private static final String ASSIGNEE = "u.enabled = 1 AND EXISTS (\n"
            + "    SELECT 1 FROM fm_user_role ur\n"
            + "    JOIN fm_role r ON r.id = ur.role_id\n"
            + "    LEFT JOIN fm_role_permission rp ON rp.role_id = r.id AND rp.permission_code = :rectification\n"
            + "    WHERE ur.user_id = u.id AND (r.code = 'ADMIN' OR rp.permission_code IS NOT NULL)\n"
            + ")\n";
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
    public ApiSupport.Page<ApprovalView> list(Authentication authentication,
                                       @RequestParam(defaultValue = "all") String view,
                                       @RequestParam(required = false) ApprovalWorkflow.Status status,
                                       @RequestParam(defaultValue = "") String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        AccessService.Actor actor = reader(authentication);
        ApiSupport.Paging paging = new ApiSupport.Paging(page, size);
        if (keyword.length() > 120) {
            throw bad("搜索词过长");
        }
        String mode;
        switch (view) {
            case "all":
                mode = "";
                break;
            case "mine":
                mode = " AND a.reporter_id = :actor";
                break;
            case "todo":
                mode = TODO;
                break;
            default:
                throw bad("未知的审批列表类型");
        }
        String filter = " WHERE " + SCOPE + mode;
        if (status != null) {
            filter += " AND a.status = :status";
        }
        if (!isBlank(keyword)) {
            filter += " AND (LOWER(a.title) LIKE LOWER(:pattern) OR LOWER(a.location) LIKE LOWER(:pattern))";
        }
        long total = listParams(jdbc.sql("SELECT COUNT(*) FROM fm_approval a" + filter), actor, view, status, keyword)
                .query(Long.class).single();
        List<Approval> rows = listParams(jdbc.page(FIELDS + filter + " ORDER BY a.id DESC", paging.offset(), paging.size()),
                actor, view, status, keyword)
                .query(Approval.class).list();
        Map<Long, ApprovalFlowRuns.Display> workflows = flows.displays(rows.stream().map(Approval::id).collect(Collectors.toList()), actor, null);
        return paging.result(rows.stream().map(row ->
                new ApprovalView(row, actions(actor, row.status(), row.assigneeId()), workflows.get(row.id()))).collect(Collectors.toList()), total);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public Details detail(Authentication authentication, @PathVariable long id) {
        AccessService.Actor actor = reader(authentication);
        Approval approval = scope(jdbc.sql(FIELDS + " WHERE a.id = :id AND " + SCOPE), actor)
                .param("id", id).query(Approval.class).optional().orElseThrow(() -> missing("隐患单不存在或无权查看"));
        List<Event> events = jdbc.sql("SELECT e.action, e.from_status, e.to_status, e.remark, e.created_at, u.display_name AS actor_name,\n"
                + "       flow.action_label, flow.from_step, flow.to_step\n"
                + "FROM fm_approval_event e JOIN fm_user u ON u.id = e.actor_id\n"
                + "LEFT JOIN fm_approval_flow_event flow ON flow.event_id = e.id\n"
                + "WHERE e.approval_id = :id ORDER BY e.id\n").param("id", id).query(Event.class).list();
        ApprovalFlowRuns.Display workflow = flows.displays(Collections.singletonList(id), actor, null).get(id);
        return new Details(approval, actions(actor, approval.status(), approval.assigneeId()), events, workflow);
    }

    @GetMapping("/assignees")
    List<Assignee> assignees(Authentication authentication, @RequestParam(defaultValue = "") String keyword) {
        AccessService.Actor actor = reader(authentication);
        actor.require(Permission.APPROVAL_ASSIGN);
        if (keyword.length() > 80) {
            throw bad("搜索词过长");
        }
        String search = strip(keyword);
        return jdbc.limit("SELECT id, username, display_name FROM fm_user u WHERE " + ASSIGNEE
                        + " AND (LOWER(display_name) LIKE LOWER(:pattern) OR LOWER(username) LIKE LOWER(:pattern)) ORDER BY display_name, id", 100)
                .param("rectification", Permission.APPROVAL_RECTIFY.code)
                .param("pattern", "%" + search + "%").query(Assignee.class).list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreatedId create(Authentication authentication, @Valid @RequestBody Create request) {
        AccessService.Actor actor = reader(authentication);
        actor.require(Permission.APPROVAL_CREATE);
        ApprovalFlowRuns.Start start = flows.beginning(ApprovalFlow.Type.WORKBENCH);
        ApprovalWorkflow.Status status = start == null ? PENDING_ASSIGNMENT : ApprovalWorkflow.Status.IN_WORKFLOW;
        long id = jdbc.sql("INSERT INTO fm_approval (id, title, location, description, reporter_id, status)\n"
                + "VALUES (:newId, :title, :location, :description, :reporter, :status)\n")
                .param("title", strip(request.title())).param("location", strip(request.location()))
                .clob("description", strip(request.description())).param("reporter", actor.id())
                .param("status", status.name()).insert();
        if (start == null) log(id, actor.id(), "CREATE", null, status, null);
        else flows.start(id, actor.id(), start, "上报隐患");
        return new CreatedId(id);
    }

    @PostMapping("/{id}/actions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void process(Authentication authentication, @PathVariable long id, @Valid @RequestBody Process request) {
        AccessService.Actor actor = reader(authentication);
        actor.require(required(request.action()));
        LockedApproval current = scope(jdbc.sql("SELECT a.status, a.assignee_id, a.version FROM fm_approval a\n"
                + "WHERE a.id = :id AND\n" + SCOPE + " FOR UPDATE"), actor).param("id", id).query(LockedApproval.class).optional()
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
        String remark = request.remark() == null ? "" : strip(request.remark());
        switch (request.action()) {
            case ASSIGN: {
                assignee = request.assigneeId();
                if (assignee == null || !canRectify(assignee)) {
                    throw ApiSupport.invalidField("assigneeId", "请选择一位启用且具有整改权限的办理人");
                }
                break;
            }
            case SUBMIT_RECTIFICATION: {
                if (!Objects.equals(assignee, actor.id())) {
                    throw forbidden();
                }
                if (isBlank(remark)) {
                    throw ApiSupport.invalidField("remark", "请填写整改说明");
                }
                break;
            }
            case RETURN: {
                if (isBlank(remark)) {
                    throw ApiSupport.invalidField("remark", "请填写退回原因");
                }
                break;
            }
            case APPROVE:
                break;
        }
        int changed = jdbc.sql("UPDATE fm_approval SET status = :status, assignee_id = :assignee,\n"
                + "    version = version + 1, updated_at = LOCALTIMESTAMP\n"
                + "WHERE id = :id AND version = :currentVersion\n").param("status", next.name()).param("assignee", assignee).param("id", id)
                .param("currentVersion", current.version()).update();
        if (changed != 1) {
            throw conflict("审批已被更新，请刷新后重试");
        }
        log(id, actor.id(), request.action().name(), current.status(), next, remark.isEmpty() ? null : remark);
    }

    private AccessService.Actor reader(Authentication authentication) {
        AccessService.Actor actor = access.actor(authentication);
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
        if (!isBlank(keyword)) {
            sql.param("pattern", "%" + strip(keyword) + "%");
        }
        return sql;
    }

    private static JdbcClient.StatementSpec scope(JdbcClient.StatementSpec sql, AccessService.Actor actor) {
        return sql.param("actor", actor.id()).param("viewAll", actor.has(Permission.APPROVAL_ALL))
                .param("canAssign", actor.has(Permission.APPROVAL_ASSIGN))
                .param("canReview", actor.has(Permission.APPROVAL_REVIEW));
    }

    private static List<ApprovalWorkflow.Action> actions(AccessService.Actor actor, ApprovalWorkflow.Status status, Long assignee) {
        ArrayList<ApprovalWorkflow.Action> result = new ArrayList<>();
        switch (status) {
            case PENDING_ASSIGNMENT: {
                if (actor.has(Permission.APPROVAL_ASSIGN)) {
                    result.add(ApprovalWorkflow.Action.ASSIGN);
                }
                break;
            }
            case PENDING_RECTIFICATION: {
                if (actor.has(Permission.APPROVAL_RECTIFY) && Objects.equals(actor.id(), assignee)) {
                    result.add(ApprovalWorkflow.Action.SUBMIT_RECTIFICATION);
                }
                break;
            }
            case PENDING_REVIEW: {
                if (actor.has(Permission.APPROVAL_REVIEW)) {
                    result.add(ApprovalWorkflow.Action.APPROVE);
                    result.add(ApprovalWorkflow.Action.RETURN);
                }
                break;
            }
            case IN_WORKFLOW:
            case CLOSED:
                break;
        }
        return result;
    }

    private static Permission required(ApprovalWorkflow.Action action) {
        switch (action) {
            case ASSIGN:
                return Permission.APPROVAL_ASSIGN;
            case SUBMIT_RECTIFICATION:
                return Permission.APPROVAL_RECTIFY;
            case APPROVE:
            case RETURN:
                return Permission.APPROVAL_REVIEW;
            default:
                throw new AssertionError(action);
        }
    }

    private void log(long approval, long actor, String action, ApprovalWorkflow.Status from,
                     ApprovalWorkflow.Status to, String remark) {
        jdbc.sql("INSERT INTO fm_approval_event (id, approval_id, actor_id, action, from_status, to_status, remark)\n"
                + "VALUES (fm_id_seq.NEXTVAL, :approval, :actor, :action, :previous, :next, :remark)\n")
                .param("approval", approval).param("actor", actor).param("action", action)
                .param("previous", from == null ? null : from.name()).param("next", to.name()).clob("remark", remark).update();
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Approval {
        long id;
        String title;
        String location;
        String description;
        long reporterId;
        String reporterName;
        Long assigneeId;
        String assigneeName;
        ApprovalWorkflow.Status status;
        int version;
        LocalDateTime createdAt;
        LocalDateTime updatedAt;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class ApprovalView {
        Approval approval;
        List<ApprovalWorkflow.Action> actions;
        ApprovalFlowRuns.Display flow;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Details {
        Approval approval;
        List<ApprovalWorkflow.Action> actions;
        List<Event> history;
        ApprovalFlowRuns.Display flow;
    }
    @Value
    static class LockedApproval {
        ApprovalWorkflow.Status status;
        Long assigneeId;
        int version;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Event {
        String action;
        String fromStatus;
        String toStatus;
        String remark;
        LocalDateTime createdAt;
        String actorName;
        String actionLabel;
        String fromStep;
        String toStep;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Assignee {
        long id;
        String username;
        String displayName;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Create {
        @NotBlank(message = "请输入隐患标题") @Size(max = 120) String title;
        @NotBlank(message = "请输入场所或位置") @Size(max = 120) String location;
        @NotBlank(message = "请描述隐患") @Size(max = 3000) String description;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Process {
        @NotNull ApprovalWorkflow.Action action;
        @NotNull @Min(0) Integer version;
        @Positive Long assigneeId;
        @Size(max = 3000) String remark;
    }
}
