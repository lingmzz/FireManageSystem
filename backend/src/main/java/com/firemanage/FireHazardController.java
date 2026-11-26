package com.firemanage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
import org.springframework.web.bind.annotation.*;

import static com.firemanage.ApiSupport.*;
import static com.firemanage.FireClosure.Action.*;
import static com.firemanage.FireClosure.Status.*;
import static com.firemanage.FireDeviceController.clean;

@RestController
@RequestMapping("/api/fire/hazards")
class FireHazardController {
    private static final String FROM = """
            FROM fm_approval a JOIN fm_fire_approval_unit au ON au.approval_id = a.id
            """;
    private static final String SELECT = """
            SELECT a.id, a.title, a.location, a.description, a.status, a.version, a.created_at, a.updated_at,
                   u.id AS unit_id, u.name AS unit_name, u.kind AS unit_kind, reporter.display_name AS reporter_name
            """ + FROM + """
            JOIN fm_fire_unit u ON u.id = au.unit_id JOIN fm_user reporter ON reporter.id = a.reporter_id
            """;
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
    Page<View> list(Authentication auth, @RequestParam(required = false) Long unitId,
                    @RequestParam(required = false) FireClosure.Status status,
                    @RequestParam(defaultValue = "") String keyword,
                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
        var scope = authorized(auth, Permission.FIRE_HAZARD_READ);
        var paging = new Paging(page, size);
        String filter = " WHERE au.unit_id IN (:units) AND (a.title LIKE :keyword OR a.location LIKE :keyword)"
                + (status == null ? "" : " AND a.status = :status");
        var units = scope.visibleIds(unitId);
        var pattern = "%" + keyword.strip() + "%";
        long total = listQuery(jdbc.sql("SELECT COUNT(*) " + FROM + filter), units, pattern, status)
                .query(Long.class).single();
        var rows = listQuery(jdbc.sql(SELECT + filter + " ORDER BY a.id DESC LIMIT :limit OFFSET :offset"), units, pattern, status)
                .param("limit", size).param("offset", paging.offset()).query(Hazard.class).list();
        var workflows = flows.displays(rows.stream().map(Hazard::id).toList(), scope.actor(), scope);
        return paging.result(rows.stream().map(row -> view(scope, row, workflows.get(row.id()))).toList(), total);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    Detail detail(Authentication auth, @PathVariable long id) {
        var scope = authorized(auth, Permission.FIRE_HAZARD_READ);
        var hazard = jdbc.sql(SELECT + " WHERE a.id = :id AND au.unit_id IN (:units)").param("id", id)
                .param("units", scope.visibleIds(null)).query(Hazard.class).optional()
                .orElseThrow(() -> missing("隐患不存在或无权查看"));
        var events = jdbc.sql("""
                SELECT e.action, e.from_status, e.to_status, e.remark, e.created_at, u.display_name AS actor_name,
                       flow.action_label, flow.from_step, flow.to_step
                FROM fm_approval_event e JOIN fm_user u ON u.id = e.actor_id
                LEFT JOIN fm_approval_flow_event flow ON flow.event_id = e.id
                WHERE e.approval_id = :id ORDER BY e.id
                """).param("id", id).query(Event.class).list();
        var flow = flows.displays(List.of(id), scope.actor(), scope).get(id);
        return new Detail(view(scope, hazard, flow), events);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    AdminController.Id create(Authentication auth, @Valid @RequestBody Form form) {
        var scope = authorized(auth, Permission.FIRE_HAZARD_ADD);
        var unit = scope.requireUnit(form.unitId());
        var start = flows.beginning(ApprovalFlow.Type.hazard(unit.kind()));
        var status = start == null ? PENDING_RECTIFICATION : IN_WORKFLOW;
        long id = insert(jdbc.sql("""
                INSERT INTO fm_approval (title, location, description, reporter_id, status)
                VALUES (:title, :location, :description, :reporter, :status)
                """).param("title", form.title().strip()).param("location", form.location().strip())
                .param("description", form.description().strip()).param("reporter", scope.actor().id())
                .param("status", status.name()));
        jdbc.sql("INSERT INTO fm_fire_approval_unit (approval_id, unit_id) VALUES (:id, :unit)")
                .param("id", id).param("unit", form.unitId()).update();
        if (start == null) log(id, scope.actor().id(), "CREATE", null, status, null);
        else flows.start(id, scope.actor().id(), start, "录入隐患");
        return new AdminController.Id(id);
    }

    @PostMapping("/{id}/actions")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void action(Authentication auth, @PathVariable long id, @Valid @RequestBody ActionForm form) {
        var scope = authorized(auth, Permission.FIRE_HAZARD_READ);
        var current = jdbc.sql("""
                SELECT a.status, a.version, au.unit_id FROM fm_approval a
                JOIN fm_fire_approval_unit au ON au.approval_id = a.id
                WHERE a.id = :id AND au.unit_id IN (:units) FOR UPDATE
                """).param("id", id).param("units", scope.visibleIds(null)).query(Locked.class)
                .optional().orElseThrow(() -> missing("隐患不存在或无权查看"));
        if (current.version() != form.version()) throw conflict("隐患已更新，请刷新后重试");
        if (!actions(scope, current.unitId(), current.status()).contains(form.action())) throw forbidden();
        String remark = clean(form.remark());
        if ((form.action() == REPORT_CLOSE || form.action() == RETURN) && remark == null) {
            throw invalidField("remark", form.action() == REPORT_CLOSE ? "请填写整改完成说明" : "请填写退回原因");
        }
        var next = FireClosure.next(current.status(), form.action(),
                scope.requireUnit(current.unitId()).kind() == FireAccess.Kind.STATION);
        jdbc.sql("""
                UPDATE fm_approval SET status = :status, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """).param("id", id).param("status", next.name()).update();
        log(id, scope.actor().id(), form.action().name(), current.status(), next, remark);
    }

    private void log(long id, long by, String action, FireClosure.Status before, FireClosure.Status after, String remark) {
        jdbc.sql("""
                INSERT INTO fm_approval_event (approval_id, actor_id, action, from_status, to_status, remark)
                VALUES (:id, :by, :action, :before, :after, :remark)
                """).param("id", id).param("by", by).param("action", action)
                .param("before", before == null ? null : before.name()).param("after", after.name()).param("remark", remark).update();
    }

    private static JdbcClient.StatementSpec listQuery(JdbcClient.StatementSpec query, List<Long> units,
                                                       String keyword, FireClosure.Status status) {
        query.param("units", units).param("keyword", keyword);
        if (status != null) query.param("status", status.name());
        return query;
    }

    private static View view(FireAccess.Scope scope, Hazard hazard, ApprovalFlowRuns.Display flow) {
        return new View(hazard, actions(scope, hazard.unitId(), hazard.status()), flow);
    }

    private static List<FireClosure.Action> actions(FireAccess.Scope scope, long unit, FireClosure.Status status) {
        var actions = new ArrayList<FireClosure.Action>(2);
        switch (status) {
            case PENDING_RECTIFICATION -> {
                if (scope.owns(unit) && scope.actor().has(Permission.FIRE_HAZARD_REPORT)) actions.add(REPORT_CLOSE);
            }
            case PENDING_DEPARTMENT_REVIEW -> {
                if (scope.hasCoverage(unit, FireAccess.Kind.DEPARTMENT) && scope.actor().has(Permission.FIRE_HAZARD_CONFIRM)) {
                    actions.add(CONFIRM_CLOSE);
                    actions.add(RETURN);
                }
            }
            case PENDING_SECTION_REVIEW -> {
                if (scope.hasCoverage(unit, FireAccess.Kind.SEGMENT) && scope.actor().has(Permission.FIRE_HAZARD_CLOSE)) {
                    actions.add(FINAL_CLOSE);
                    actions.add(RETURN);
                }
            }
            case IN_WORKFLOW, CLOSED -> { }
        }
        return actions;
    }

    private FireAccess.Scope authorized(Authentication auth, Permission permission) {
        var scope = fire.scope(auth);
        scope.actor().require(permission);
        return scope;
    }

    record Form(@NotNull @Positive Long unitId, @NotBlank @Size(max = 120) String title,
                @NotBlank @Size(max = 120) String location, @NotBlank @Size(max = 3000) String description) {}
    record ActionForm(@NotNull FireClosure.Action action, @NotNull @Min(0) Integer version,
                      @Size(max = 3000) String remark) {}
    record Hazard(long id, String title, String location, String description, FireClosure.Status status, int version,
                  LocalDateTime createdAt, LocalDateTime updatedAt, long unitId, String unitName,
                  FireAccess.Kind unitKind, String reporterName) {}
    record View(Hazard hazard, List<FireClosure.Action> actions, ApprovalFlowRuns.Display flow) {}
    record Locked(FireClosure.Status status, int version, long unitId) {}
    record Event(String action, String fromStatus, String toStatus, String remark, LocalDateTime createdAt, String actorName,
                 String actionLabel, String fromStep, String toStep) {}
    record Detail(View item, List<Event> history) {}
}
