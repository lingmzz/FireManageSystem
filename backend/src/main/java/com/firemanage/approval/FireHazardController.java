package com.firemanage.approval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import static com.firemanage.approval.FireClosure.Action.*;
import static com.firemanage.approval.FireClosure.Status.*;
import static com.firemanage.common.ApiSupport.*;
import static org.apache.commons.lang3.StringUtils.strip;
import static org.apache.commons.lang3.StringUtils.stripToNull;

@RestController
@RequestMapping("/api/fire/hazards")
class FireHazardController {
    private static final String FROM = "FROM fm_approval a JOIN fm_fire_approval_unit au ON au.approval_id = a.id\n";
    private static final String SELECT = "SELECT a.id, a.title, a.location, a.description, a.status, a.version, a.created_at, a.updated_at,\n"
            + "       u.id AS unit_id, u.name AS unit_name, u.kind AS unit_kind, reporter.display_name AS reporter_name\n" + FROM + "JOIN fm_fire_unit u ON u.id = au.unit_id JOIN fm_user reporter ON reporter.id = a.reporter_id\n";
    private final FireAccess fire;
    private final JdbcClient jdbc;
    private final ApprovalFlowRuns flows;

    FireHazardController(FireAccess fire, JdbcClient jdbc, ApprovalFlowRuns flows) {
        this.fire = fire;
        this.jdbc = jdbc;
        this.flows = flows;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<View> list(Authentication auth, @RequestParam(required = false) Long unitId,
                    @RequestParam(required = false) FireClosure.Status status,
                    @RequestParam(defaultValue = "") String keyword,
                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_HAZARD_READ);
        ApiSupport.Paging paging = new Paging(page, size);
        JdbcClient.IdList units = new JdbcClient.IdList(scope.visibleIds(unitId));
        String filter = " WHERE " + units.matches("au.unit_id")
                + " AND (LOWER(a.title) LIKE LOWER(:keyword) OR LOWER(a.location) LIKE LOWER(:keyword))"
                + (status == null ? "" : " AND a.status = :status");
        String pattern = "%" + strip(keyword) + "%";
        long total = listQuery(jdbc.sql("SELECT COUNT(*) " + FROM + filter), units, pattern, status)
                .query(Long.class).single();
        List<Hazard> rows = listQuery(jdbc.page(SELECT + filter + " ORDER BY a.id DESC", paging.offset(), paging.size()),
                units, pattern, status).query(Hazard.class).list();
        Map<Long, ApprovalFlowRuns.Display> workflows = flows.displays(rows.stream().map(Hazard::id).collect(Collectors.toList()), scope.actor(), scope);
        return paging.result(rows.stream().map(row -> view(scope, row, workflows.get(row.id()))).collect(Collectors.toList()), total);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public Detail detail(Authentication auth, @PathVariable long id) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_HAZARD_READ);
        JdbcClient.IdList units = new JdbcClient.IdList(scope.visibleIds(null));
        Hazard hazard = jdbc.sql(SELECT + " WHERE a.id = :id AND " + units.matches("au.unit_id"))
                .param("id", id).params(units.parameters()).query(Hazard.class).optional()
                .orElseThrow(() -> missing("隐患不存在或无权查看"));
        List<Event> events = jdbc.sql("SELECT e.action, e.from_status, e.to_status, e.remark, e.created_at, u.display_name AS actor_name,\n"
                + "       flow.action_label, flow.from_step, flow.to_step\n"
                + "FROM fm_approval_event e JOIN fm_user u ON u.id = e.actor_id\n"
                + "LEFT JOIN fm_approval_flow_event flow ON flow.event_id = e.id\n"
                + "WHERE e.approval_id = :id ORDER BY e.id\n").param("id", id).query(Event.class).list();
        ApprovalFlowRuns.Display flow = flows.displays(Collections.singletonList(id), scope.actor(), scope).get(id);
        return new Detail(view(scope, hazard, flow), events);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedId create(Authentication auth, @Valid @RequestBody Form form) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_HAZARD_ADD);
        FireAccess.Unit unit = scope.requireUnit(form.unitId());
        ApprovalFlowRuns.Start start = flows.beginning(ApprovalFlow.Type.hazard(unit.kind()));
        FireClosure.Status status = start == null ? PENDING_RECTIFICATION : IN_WORKFLOW;
        long id = jdbc.sql("INSERT INTO fm_approval (id, title, location, description, reporter_id, status)\n"
                + "VALUES (:newId, :title, :location, :description, :reporter, :status)\n")
                .param("title", strip(form.title())).param("location", strip(form.location()))
                .clob("description", strip(form.description())).param("reporter", scope.actor().id())
                .param("status", status.name()).insert();
        jdbc.sql("INSERT INTO fm_fire_approval_unit (approval_id, unit_id) VALUES (:id, :unit)")
                .param("id", id).param("unit", form.unitId()).update();
        if (start == null) log(id, scope.actor().id(), "CREATE", null, status, null);
        else flows.start(id, scope.actor().id(), start, "录入隐患");
        return new CreatedId(id);
    }

    @PostMapping("/{id}/actions")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void action(Authentication auth, @PathVariable long id, @Valid @RequestBody ActionForm form) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_HAZARD_READ);
        JdbcClient.IdList units = new JdbcClient.IdList(scope.visibleIds(null));
        Locked current = jdbc.sql("SELECT a.status, a.version, au.unit_id FROM fm_approval a\n"
                + "JOIN fm_fire_approval_unit au ON au.approval_id = a.id\n"
                + "WHERE a.id = :id AND " + units.matches("au.unit_id") + " FOR UPDATE OF a.status")
                .param("id", id).params(units.parameters()).query(Locked.class)
                .optional().orElseThrow(() -> missing("隐患不存在或无权查看"));
        if (current.version() != form.version()) throw conflict("隐患已更新，请刷新后重试");
        if (!actions(scope, current.unitId(), current.status()).contains(form.action())) throw forbidden();
        String remark = stripToNull(form.remark());
        if ((form.action() == REPORT_CLOSE || form.action() == RETURN) && remark == null) {
            throw invalidField("remark", form.action() == REPORT_CLOSE ? "请填写整改完成说明" : "请填写退回原因");
        }
        FireClosure.Status next = FireClosure.next(current.status(), form.action(),
                scope.requireUnit(current.unitId()).kind() == FireAccess.Kind.STATION);
        jdbc.sql("UPDATE fm_approval SET status = :status, version = version + 1, updated_at = LOCALTIMESTAMP\n"
                + "WHERE id = :id\n").param("id", id).param("status", next.name()).update();
        log(id, scope.actor().id(), form.action().name(), current.status(), next, remark);
    }

    private void log(long id, long by, String action, FireClosure.Status before, FireClosure.Status after, String remark) {
        jdbc.sql("INSERT INTO fm_approval_event (id, approval_id, actor_id, action, from_status, to_status, remark)\n"
                + "VALUES (fm_id_seq.NEXTVAL, :id, :by, :action, :before, :after, :remark)\n").param("id", id).param("by", by).param("action", action)
                .param("before", before == null ? null : before.name()).param("after", after.name()).clob("remark", remark).update();
    }

    private static JdbcClient.StatementSpec listQuery(JdbcClient.StatementSpec query, JdbcClient.IdList units,
                                                       String keyword, FireClosure.Status status) {
        query.params(units.parameters()).param("keyword", keyword);
        if (status != null) query.param("status", status.name());
        return query;
    }

    private static View view(FireAccess.Scope scope, Hazard hazard, ApprovalFlowRuns.Display flow) {
        return new View(hazard, actions(scope, hazard.unitId(), hazard.status()), flow);
    }

    private static List<FireClosure.Action> actions(FireAccess.Scope scope, long unit, FireClosure.Status status) {
        ArrayList<FireClosure.Action> actions = new ArrayList<>(2);
        switch (status) {
            case PENDING_RECTIFICATION: {
                if (scope.owns(unit) && scope.actor().has(Permission.FIRE_HAZARD_REPORT)) actions.add(REPORT_CLOSE);
                break;
            }
            case PENDING_DEPARTMENT_REVIEW: {
                if (scope.hasCoverage(unit, FireAccess.Kind.DEPARTMENT) && scope.actor().has(Permission.FIRE_HAZARD_CONFIRM)) {
                    actions.add(CONFIRM_CLOSE);
                    actions.add(RETURN);
                }
                break;
            }
            case PENDING_SECTION_REVIEW: {
                if (scope.hasCoverage(unit, FireAccess.Kind.SEGMENT) && scope.actor().has(Permission.FIRE_HAZARD_CLOSE)) {
                    actions.add(FINAL_CLOSE);
                    actions.add(RETURN);
                }
                break;
            }
            case IN_WORKFLOW:
            case CLOSED:
                break;
        }
        return actions;
    }

    private FireAccess.Scope authorized(Authentication auth, Permission permission) {
        FireAccess.Scope scope = fire.scope(auth);
        scope.actor().require(permission);
        return scope;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Form {
        @NotNull @Positive Long unitId;
        @NotBlank @Size(max = 120) String title;
        @NotBlank @Size(max = 120) String location;
        @NotBlank @Size(max = 3000) String description;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class ActionForm {
        @NotNull FireClosure.Action action;
        @NotNull @Min(0) Integer version;
        @Size(max = 3000) String remark;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Hazard {
        long id;
        String title;
        String location;
        String description;
        FireClosure.Status status;
        int version;
        LocalDateTime createdAt;
        LocalDateTime updatedAt;
        long unitId;
        String unitName;
        FireAccess.Kind unitKind;
        String reporterName;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class View {
        Hazard hazard;
        List<FireClosure.Action> actions;
        ApprovalFlowRuns.Display flow;
    }
    @Value
    static class Locked {
        FireClosure.Status status;
        int version;
        long unitId;
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
    static class Detail {
        View item;
        List<Event> history;
    }
}
