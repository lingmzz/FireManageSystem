package com.firemanage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.firemanage.ApiSupport.*;

@RestController
@RequestMapping("/api/fire/devices")
class FireDeviceController {
    static final int SCRAPPED = 0;
    static final int IN_USE = 1;
    static final int STOCK = 2;
    static final ZoneId LOCAL_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String FROM = """
            FROM fm_fire_device d JOIN fm_fire_unit u ON u.id = d.unit_id
            """;
    private static final String SELECT = """
            SELECT d.id, d.unit_id, u.name AS unit_name, u.code AS unit_code, d.code, d.category,
                   d.item_name, d.model_type, d.specification, d.use_site, d.storage_location,
                   d.manufactured_on, d.inspected_on, d.expires_on, d.xfqc_bs AS state, d.version,
                   d.created_role, d.created_at, d.updated_at
            """ + FROM;
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String LOCK = """
            SELECT id, unit_id, category, xfqc_bs AS state, version, use_site, storage_location,
                   manufactured_on, inspected_on, deleted FROM fm_fire_device
            """;

    private final JdbcClient jdbc;
    private final FireAccess fire;
    private final FireDeviceExcel excel;

    FireDeviceController(JdbcClient jdbc, FireAccess fire, FireDeviceExcel excel) {
        this.jdbc = jdbc;
        this.fire = fire;
        this.excel = excel;
    }

    @GetMapping
    @Transactional(readOnly = true)
    Page<DeviceView> list(Authentication auth, @ModelAttribute Search search,
                          @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
        var scope = authorized(auth, Permission.FIRE_DEVICE_READ);
        var paging = new Paging(page, size);
        LocalDate today = LocalDate.now(LOCAL_ZONE);
        var filter = filter(scope, search, today);
        long total = jdbc.sql("SELECT COUNT(*) " + FROM + filter.where()).params(filter.values()).query(Long.class).single();
        var rows = jdbc.sql(SELECT + filter.where() + " ORDER BY d.id DESC LIMIT :limit OFFSET :offset")
                .params(filter.values()).param("limit", size).param("offset", paging.offset()).query(Device.class).list();
        return paging.result(rows.stream().map(row -> view(scope, row, today)).toList(), total);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    Detail detail(Authentication auth, @PathVariable long id) {
        var scope = authorized(auth, Permission.FIRE_DEVICE_READ);
        var device = liveDevice(id);
        scope.requireUnit(device.unitId());
        var events = jdbc.sql("""
                SELECT e.operation, e.from_state, e.to_state, e.inspected_on, e.remark, e.created_at,
                       u.display_name AS actor_name, r.code AS related_code
                FROM fm_fire_device_event e JOIN fm_user u ON u.id = e.actor_id
                LEFT JOIN fm_fire_device r ON r.id = e.related_device_id
                WHERE e.device_id = :device ORDER BY e.id DESC
                """).param("device", id).query(Event.class).list();
        var maintainers = scope.actor().has(Permission.FIRE_MAINT_READ) ? jdbc.sql("""
                SELECT m.id, m.name, m.contact, m.phone FROM fm_fire_maintainer m
                JOIN fm_fire_maintainer_device d ON d.maintainer_id = m.id
                WHERE d.device_id = :device AND NOT m.deleted ORDER BY m.name
                """).param("device", id).query(MaintainerSummary.class).list() : List.<MaintainerSummary>of();
        return new Detail(view(scope, device, LocalDate.now(LOCAL_ZONE)), events, maintainers);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    AdminController.Id create(Authentication auth, @Valid @RequestBody Input request) {
        try {
            return new AdminController.Id(add(authorized(auth, Permission.FIRE_DEVICE_ADD), request));
        } catch (DuplicateKeyException failure) {
            throw duplicateField("code", "该设备编号已存在");
        }
    }

    @PutMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void edit(Authentication auth, @PathVariable long id, @Valid @RequestBody Input request) {
        var scope = authorized(auth, Permission.FIRE_DEVICE_EDIT);
        var current = lock(id);
        active(scope, current);
        requireVersion(current, request.version());
        if (!scope.fullDeviceEdit(current.unitId())) throw forbidden();
        if (current.unitId() != request.unitId()) throw invalidField("unitId", "设备的所属单位不能通过编辑修改");
        validateDates(request.manufacturedOn(), current.inspectedOn(), request.expiresOn(), "manufacturedOn");
        try {
            fields(jdbc.sql("""
                    UPDATE fm_fire_device SET code = :code, category = :category, item_name = :name,
                        model_type = :type, specification = :spec, use_site = :site, storage_location = :location,
                        manufactured_on = :manufactured, expires_on = :expiry, version = version + 1
                    WHERE id = :id
                    """), request).param("id", id).update();
        } catch (DuplicateKeyException failure) {
            throw duplicateField("code", "该设备编号已存在");
        }
        event(id, scope, "EDIT", current.state(), current.state(), null, null, null);
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(Authentication auth, @PathVariable long id) {
        var scope = authorized(auth, Permission.FIRE_DEVICE_EDIT);
        var current = lock(id);
        if (current.deleted()) throw missing("设备不存在");
        if (!scope.fullDeviceEdit(current.unitId())) throw forbidden();
        jdbc.sql("UPDATE fm_fire_device SET deleted = TRUE, version = version + 1 WHERE id = :id")
                .param("id", id).update();
        event(id, scope, "DELETE", current.state(), current.state(), null, null, null);
    }

    @PostMapping("/{id}/actions")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void operate(Authentication auth, @PathVariable long id, @Valid @RequestBody Operation request) {
        var scope = authorized(auth, Permission.FIRE_DEVICE_OPERATE);
        if (request.action() == Action.REPLACE) {
            replace(scope, id, request);
            return;
        }
        var current = lock(id);
        active(scope, current);
        requireVersion(current, request.version());
        apply(scope, current, request.action(), request.inspectedOn(), request.useSite(), request.storageLocation(), request.remark());
    }

    @PostMapping("/batch")
    @Transactional
    BatchResult batch(Authentication auth, @Valid @RequestBody Batch request) {
        var scope = authorized(auth, Permission.FIRE_DEVICE_OPERATE);
        if (request.action() != Action.INSPECT && request.action() != Action.SCRAP) {
            throw bad("批量操作支持年检和报废");
        }
        var versions = new HashMap<Long, Integer>();
        for (var item : request.devices()) {
            if (versions.put(item.id(), item.version()) != null) throw bad("批量选择中有重复设备");
        }
        var rows = lockAll(versions.keySet().stream().toList());
        if (rows.size() != versions.size()) throw missing("批量选择中包含不存在的设备");
        for (Locked device : rows) {
            active(scope, device);
            requireVersion(device, versions.get(device.id()));
            apply(scope, device, request.action(), request.inspectedOn(), null, null, request.remark());
        }
        return new BatchResult(rows.size());
    }

    @GetMapping("/template")
    void template(Authentication auth, HttpServletResponse response) throws IOException {
        authorized(auth, Permission.FIRE_DEVICE_IMPORT);
        workbook(response, "消防设备导入模板.xlsx");
        excel.writeTemplate(response.getOutputStream());
    }

    @PostMapping("/import")
    @Transactional
    BatchResult importDevices(Authentication auth, @RequestParam("file") MultipartFile file) {
        var scope = authorized(auth, Permission.FIRE_DEVICE_IMPORT);
        scope.actor().require(Permission.FIRE_DEVICE_ADD);
        var units = scope.visibleUnits().stream().collect(Collectors.toMap(FireAccess.Unit::code, FireAccess.Unit::id));
        var requests = excel.read(file, units);
        for (var row : requests) {
            try {
                if (!scope.fullDeviceEdit(row.input().unitId())) throw forbidden();
                add(scope, row.input());
            } catch (DuplicateKeyException failure) {
                throw bad("第 " + row.number() + " 行设备编号已存在或重复");
            } catch (org.springframework.web.server.ResponseStatusException failure) {
                throw bad("第 " + row.number() + " 行：" + failure.getReason());
            }
        }
        return new BatchResult(requests.size());
    }

    @GetMapping("/export")
    void export(Authentication auth, @ModelAttribute Search search, HttpServletResponse response) throws IOException {
        var scope = authorized(auth, Permission.FIRE_DEVICE_READ);
        var today = LocalDate.now(LOCAL_ZONE);
        var filter = filter(scope, search, today);
        workbook(response, "消防设备台账.xlsx");
        excel.writeExport(response.getOutputStream(), after -> jdbc.sql(SELECT + filter.where()
                        + " AND d.id > :after ORDER BY d.id LIMIT 500")
                .params(filter.values()).param("after", after).query(Device.class).list(), today);
    }

    private long add(FireAccess.Scope scope, Input request) {
        scope.requireUnit(request.unitId());
        validateDates(request.manufacturedOn(), request.inspectedOn(), request.expiresOn(), "inspectedOn");
        long id = insert(fields(jdbc.sql("""
                INSERT INTO fm_fire_device
                    (unit_id, code, category, item_name, model_type, specification, use_site, storage_location,
                     manufactured_on, inspected_on, expires_on, created_by, created_role)
                VALUES (:unit, :code, :category, :name, :type, :spec, :site, :location,
                        :manufactured, :inspection, :expiry, :actor, :role)
                """), request).param("unit", request.unitId()).param("inspection", request.inspectedOn())
                .param("actor", scope.actor().id()).param("role", String.join(",", scope.actor().roles()
                        .stream().filter(code -> code.startsWith("FIRE_") || code.equals(AccessService.ADMIN_ROLE)).toList())));
        event(id, scope, "CREATE", null, STOCK, request.inspectedOn(), null, null);
        return id;
    }

    private static JdbcClient.StatementSpec fields(JdbcClient.StatementSpec stmt, Input input) {
        return stmt.param("code", input.code().strip()).param("category", input.category().name())
                .param("name", input.itemName().strip()).param("type", clean(input.modelType()))
                .param("spec", clean(input.specification())).param("site", clean(input.useSite()))
                .param("location", input.storageLocation().strip()).param("manufactured", input.manufacturedOn())
                .param("expiry", input.expiresOn());
    }

    private void apply(FireAccess.Scope scope, Locked device, Action action, LocalDate inspection, String site,
                       String storage, String remark) {
        int target = device.state();
        switch (action) {
            case MOVE -> updatePlace(device.id(), target, device.useSite(),
                    required(storage, "storageLocation", "请输入存放地点"));
            case INSPECT -> {
                if (inspection == null) throw invalidField("inspectedOn", "请填写本次年检日期");
                validateDates(device.manufacturedOn(), inspection, null, "inspectedOn");
                var latest = device.inspectedOn() == null || inspection.isAfter(device.inspectedOn())
                        ? inspection : device.inspectedOn();
                jdbc.sql("UPDATE fm_fire_device SET inspected_on = :date, version = version + 1 WHERE id = :id")
                        .param("date", latest).param("id", device.id()).update();
            }
            case ISSUE -> {
                if (device.state() != STOCK) throw conflict("当前设备不在库存");
                target = IN_USE;
                updatePlace(device.id(), target, required(site, "useSite", "请输入使用场所"),
                        clean(storage) == null ? device.storageLocation() : storage.strip());
            }
            case SCRAP -> {
                target = SCRAPPED;
                updatePlace(device.id(), target, device.useSite(), device.storageLocation());
            }
            case REPLACE -> throw bad("更换设备必须选择替换设备");
        }
        event(device.id(), scope, action.name(), device.state(), target,
                action == Action.INSPECT ? inspection : null, null, clean(remark));
    }

    private void replace(FireAccess.Scope scope, long id, Operation request) {
        if (request.replacementId() == null || request.replacementId() == id) {
            throw invalidField("replacementId", "请选择替换设备");
        }
        var pair = lockAll(List.of(id, request.replacementId()));
        if (pair.size() != 2) throw missing("要更换的设备不存在");
        Locked old = pair.get(0).id() == id ? pair.get(0) : pair.get(1);
        Locked next = pair.get(0).id() == id ? pair.get(1) : pair.get(0);
        active(scope, old);
        active(scope, next);
        requireVersion(old, request.version());
        if (old.state() != IN_USE || next.state() != STOCK) throw conflict("更换操作需要在用设备和库存设备");
        if (old.unitId() != next.unitId() || old.category() != next.category()) {
            throw invalidField("replacementId", "请选择同一单位、同类的库存设备");
        }
        updatePlace(old.id(), STOCK, null,
                clean(request.storageLocation()) == null ? old.storageLocation() : request.storageLocation().strip());
        updatePlace(next.id(), IN_USE, old.useSite(), old.storageLocation());
        event(old.id(), scope, "REPLACE_OUT", IN_USE, STOCK, null, next.id(), clean(request.remark()));
        event(next.id(), scope, "REPLACE_IN", STOCK, IN_USE, null, old.id(), clean(request.remark()));
    }

    private void updatePlace(long id, int state, String site, String location) {
        jdbc.sql("""
                UPDATE fm_fire_device SET xfqc_bs = :state, use_site = :site, storage_location = :location,
                    version = version + 1 WHERE id = :id
                """).param("state", state).param("site", site).param("location", location).param("id", id).update();
    }

    private void event(long id, FireAccess.Scope scope, String operation, Integer from, int to,
                       LocalDate inspection, Long related, String remark) {
        jdbc.sql("""
                INSERT INTO fm_fire_device_event
                    (device_id, actor_id, operation, from_state, to_state, inspected_on, related_device_id, remark)
                VALUES (:device, :actor, :operation, :previous, :next, :inspection, :related, :remark)
                """).param("device", id).param("actor", scope.actor().id()).param("operation", operation)
                .param("previous", from).param("next", to).param("inspection", inspection)
                .param("related", related).param("remark", remark).update();
    }

    private Locked lock(long id) {
        return jdbc.sql(LOCK + " WHERE id = :id FOR UPDATE").param("id", id).query(Locked.class)
                .optional().orElseThrow(() -> missing("设备不存在"));
    }

    private List<Locked> lockAll(List<Long> ids) {
        return jdbc.sql(LOCK + " WHERE id IN (:ids) ORDER BY id FOR UPDATE").param("ids", ids).query(Locked.class).list();
    }

    private Device liveDevice(long id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id AND NOT d.deleted").param("id", id).query(Device.class)
                .optional().orElseThrow(() -> missing("设备不存在"));
    }

    private FireAccess.Scope authorized(Authentication auth, Permission permission) {
        var scope = fire.scope(auth);
        scope.actor().require(permission);
        return scope;
    }

    private static void active(FireAccess.Scope scope, Locked device) {
        if (device.deleted()) throw missing("设备不存在");
        scope.requireUnit(device.unitId());
        if (device.state() == SCRAPPED) throw conflict("设备已报废");
    }

    private static void requireVersion(Locked device, Integer version) {
        if (!Objects.equals(device.version(), version)) throw conflict("设备已更新，请刷新后重试");
    }

    static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String required(String value, String field, String message) {
        String result = clean(value);
        if (result == null) throw invalidField(field, message);
        return result;
    }

    private static void validateDates(LocalDate manufactured, LocalDate inspected, LocalDate expires, String inspectionField) {
        if (manufactured != null && expires != null && expires.isBefore(manufactured)) {
            throw invalidField("expiresOn", "保质期截止日期不能早于出厂日期");
        }
        if (inspected != null && (inspected.isAfter(LocalDate.now(LOCAL_ZONE))
                || manufactured != null && inspected.isBefore(manufactured))) {
            throw invalidField(inspectionField, "年检日期应在出厂之后且不能晚于今天");
        }
    }

    private static DeviceView view(FireAccess.Scope scope, Device row, LocalDate today) {
        var notices = FireReminder.forDevice(row.state(), row.expiresOn(), row.inspectedOn(), today)
                .stream().map(n -> n.code).toList();
        return new DeviceView(row, notices, scope.fullDeviceEdit(row.unitId()), scope.actor().has(Permission.FIRE_DEVICE_OPERATE));
    }

    private static Filter filter(FireAccess.Scope scope, Search search, LocalDate today) {
        var sql = new StringBuilder(" WHERE NOT d.deleted AND d.unit_id IN (:units)");
        Map<String, Object> params = new HashMap<>();
        params.put("units", scope.visibleIds(search.unitId()));
        if (search.state() != null) {
            if (search.state() < 0 || search.state() > 2) throw bad("设备状态无效");
            sql.append(" AND d.xfqc_bs = :state");
            params.put("state", search.state());
        }
        if (search.category() != null) {
            sql.append(" AND d.category = :category");
            params.put("category", search.category().name());
        }
        if (clean(search.keyword()) != null) {
            if (search.keyword().length() > 120) throw bad("搜索词过长");
            sql.append(" AND (d.code LIKE :keyword OR d.item_name LIKE :keyword OR d.storage_location LIKE :keyword OR d.use_site LIKE :keyword)");
            params.put("keyword", "%" + search.keyword().strip() + "%");
        }
        if (search.notice() != null) {
            sql.append(switch (search.notice()) {
                case 2 -> " AND d.xfqc_bs IN (1, 2) AND d.expires_on > :m3 AND d.expires_on <= :m6";
                case 3 -> " AND d.xfqc_bs IN (1, 2) AND d.expires_on >= :today AND d.expires_on <= :m3";
                case 9 -> " AND d.xfqc_bs IN (1, 2) AND d.expires_on < :today";
                case 5 -> " AND d.xfqc_bs = 1 AND (d.inspected_on < :yearStart OR d.inspected_on >= :nextYear)";
                case 6 -> " AND d.xfqc_bs = 1 AND d.inspected_on IS NULL";
                default -> throw bad("提示类型无效");
            });
            params.put("today", today);
            params.put("m3", today.plusMonths(3));
            params.put("m6", today.plusMonths(6));
            params.put("yearStart", today.withDayOfYear(1));
            params.put("nextYear", today.withDayOfYear(1).plusYears(1));
        }
        return new Filter(sql.toString(), params);
    }

    private static void workbook(HttpServletResponse response, String filename) {
        response.setContentType(XLSX);
        response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString());
    }

    enum Category {
        EXTINGUISHER("灭火器"), HYDRANT("消火栓"), SMOKE_ALARM("烟感报警器"), HOSE("消防水带"),
        EMERGENCY_CABINET("消防应急柜"), RESPIRATOR("防毒面罩"), OTHER("其它");

        final String label;
        Category(String label) { this.label = label; }
    }
    enum Action { MOVE, INSPECT, ISSUE, REPLACE, SCRAP }
    record Search(Long unitId, Category category, Integer state, Integer notice, String keyword) {}
    record Filter(String where, Map<String, Object> values) {}
    record Input(@NotNull @Positive Long unitId, @NotBlank @Size(max = 64) String code,
                 @NotNull Category category, @NotBlank @Size(max = 100) String itemName,
                 @Size(max = 100) String modelType, @Size(max = 100) String specification,
                 @Size(max = 180) String useSite, @NotBlank @Size(max = 180) String storageLocation,
                 LocalDate manufacturedOn, LocalDate inspectedOn, LocalDate expiresOn, @Min(0) Integer version) {}
    record Operation(@NotNull Action action, @NotNull @Min(0) Integer version, LocalDate inspectedOn,
                     @Size(max = 180) String useSite, @Size(max = 180) String storageLocation,
                     @Positive Long replacementId, @Size(max = 500) String remark) {}
    record Versioned(@NotNull @Positive Long id, @NotNull @Min(0) Integer version) {}
    record Batch(@NotNull Action action, @NotEmpty @Size(max = 500) List<@NotNull @Valid Versioned> devices,
                 LocalDate inspectedOn, @Size(max = 500) String remark) {}
    record BatchResult(int count) {}
    record Device(long id, long unitId, String unitName, String unitCode, String code, Category category,
                  String itemName, String modelType, String specification, String useSite, String storageLocation,
                  LocalDate manufacturedOn, LocalDate inspectedOn, LocalDate expiresOn, int state, int version,
                  String createdRole, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    record Locked(long id, long unitId, Category category, int state, int version, String useSite,
                  String storageLocation, LocalDate manufacturedOn, LocalDate inspectedOn, boolean deleted) {}
    record DeviceView(Device device, List<Integer> notices, boolean fullEdit, boolean operate) {}
    record Event(String operation, Integer fromState, int toState, LocalDate inspectedOn, String remark,
                 LocalDateTime createdAt, String actorName, String relatedCode) {}
    record MaintainerSummary(long id, String name, String contact, String phone) {}
    record Detail(DeviceView item, List<Event> history, List<MaintainerSummary> maintainers) {}
}
