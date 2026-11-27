package com.firemanage.device;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

import com.firemanage.common.ApiSupport;
import com.firemanage.common.BusinessTime;
import com.firemanage.common.CreatedId;
import com.firemanage.common.JdbcClient;
import com.firemanage.identity.AccessService;
import com.firemanage.identity.Permission;
import com.firemanage.organization.FireAccess;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.firemanage.common.ApiSupport.*;
import static com.firemanage.device.DeviceState.IN_USE;
import static com.firemanage.device.DeviceState.SCRAPPED;
import static com.firemanage.device.DeviceState.STOCK;
import static org.apache.commons.lang3.StringUtils.strip;
import static org.apache.commons.lang3.StringUtils.stripToNull;

@RestController
@RequestMapping("/api/fire/devices")
class FireDeviceController {

    private static final String FROM = "FROM fm_fire_device d JOIN fm_fire_unit u ON u.id = d.unit_id\n";
    private static final String SELECT = "SELECT d.id, d.unit_id, u.name AS unit_name, u.code AS unit_code, d.code, d.category,\n"
            + "       d.item_name, d.model_type, d.specification, d.use_site, d.storage_location,\n"
            + "       d.manufactured_on, d.inspected_on, d.expires_on, d.xfqc_bs AS state, d.version,\n"
            + "       d.created_role, d.created_at, d.updated_at\n" + FROM;
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String LOCK = "SELECT id, unit_id, category, xfqc_bs AS state, version, use_site, storage_location,\n"
            + "       manufactured_on, inspected_on, deleted FROM fm_fire_device\n";

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
    public Page<DeviceView> list(Authentication auth, @ModelAttribute Search search,
                          @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DEVICE_READ);
        ApiSupport.Paging paging = new Paging(page, size);
        LocalDate today = LocalDate.now(BusinessTime.ZONE);
        Filter filter = filter(scope, search, today);
        long total = jdbc.sql("SELECT COUNT(*) " + FROM + filter.where()).params(filter.values()).query(Long.class).single();
        List<Device> rows = jdbc.page(SELECT + filter.where() + " ORDER BY d.id DESC", paging.offset(), paging.size())
                .params(filter.values()).query(Device.class).list();
        return paging.result(rows.stream().map(row -> view(scope, row, today)).collect(Collectors.toList()), total);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public Detail detail(Authentication auth, @PathVariable long id) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DEVICE_READ);
        Device device = liveDevice(id);
        scope.requireUnit(device.unitId());
        List<Event> events = jdbc.sql("SELECT e.operation, e.from_state, e.to_state, e.inspected_on, e.remark, e.created_at,\n"
                + "       u.display_name AS actor_name, r.code AS related_code\n"
                + "FROM fm_fire_device_event e JOIN fm_user u ON u.id = e.actor_id\n"
                + "LEFT JOIN fm_fire_device r ON r.id = e.related_device_id\n"
                + "WHERE e.device_id = :device ORDER BY e.id DESC\n").param("device", id).query(Event.class).list();
        List<MaintainerSummary> maintainers = scope.actor().has(Permission.FIRE_MAINT_READ) ? jdbc.sql("SELECT m.id, m.name, m.contact, m.phone FROM fm_fire_maintainer m\n"
                + "JOIN fm_fire_maintainer_device d ON d.maintainer_id = m.id\n"
                + "WHERE d.device_id = :device AND m.deleted = 0 ORDER BY m.name\n")
                .param("device", id).query(MaintainerSummary.class).list() : Collections.emptyList();
        return new Detail(view(scope, device, LocalDate.now(BusinessTime.ZONE)), events, maintainers);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedId create(Authentication auth, @Valid @RequestBody Input request) {
        try {
            return new CreatedId(add(authorized(auth, Permission.FIRE_DEVICE_ADD), request));
        } catch (DuplicateKeyException failure) {
            throw duplicateField("code", "该设备编号已存在");
        }
    }

    @PutMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void edit(Authentication auth, @PathVariable long id, @Valid @RequestBody Input request) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DEVICE_EDIT);
        Locked current = lock(id);
        active(scope, current);
        requireVersion(current, request.version());
        if (!scope.fullDeviceEdit(current.unitId())) throw forbidden();
        if (current.unitId() != request.unitId()) throw invalidField("unitId", "设备的所属单位不能通过编辑修改");
        validateDates(request.manufacturedOn(), current.inspectedOn(), request.expiresOn(), "manufacturedOn");
        try {
            fields(jdbc.sql("UPDATE fm_fire_device SET updated_at = LOCALTIMESTAMP, code = :code, category = :category, item_name = :name,\n"
                    + "    model_type = :type, specification = :spec, use_site = :site, storage_location = :location,\n"
                    + "    manufactured_on = :manufactured, expires_on = :expiry, version = version + 1\n"
                    + "WHERE id = :id\n"), request).param("id", id).update();
        } catch (DuplicateKeyException failure) {
            throw duplicateField("code", "该设备编号已存在");
        }
        event(id, scope, "EDIT", current.state(), current.state(), null, null, null);
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication auth, @PathVariable long id) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DEVICE_EDIT);
        Locked current = lock(id);
        if (current.deleted()) throw missing("设备不存在");
        if (!scope.fullDeviceEdit(current.unitId())) throw forbidden();
        jdbc.sql("UPDATE fm_fire_device SET updated_at = LOCALTIMESTAMP, deleted = 1, version = version + 1 WHERE id = :id")
                .param("id", id).update();
        event(id, scope, "DELETE", current.state(), current.state(), null, null, null);
    }

    @PostMapping("/{id}/actions")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void operate(Authentication auth, @PathVariable long id, @Valid @RequestBody Operation request) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DEVICE_OPERATE);
        if (request.action() == Action.REPLACE) {
            replace(scope, id, request);
            return;
        }
        Locked current = lock(id);
        active(scope, current);
        requireVersion(current, request.version());
        apply(scope, current, request.action(), request.inspectedOn(), request.useSite(), request.storageLocation(), request.remark());
    }

    @PostMapping("/batch")
    @Transactional
    public BatchResult batch(Authentication auth, @Valid @RequestBody Batch request) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DEVICE_OPERATE);
        if (request.action() != Action.INSPECT && request.action() != Action.SCRAP) {
            throw bad("批量操作支持年检和报废");
        }
        HashMap<Long, Integer> versions = new HashMap<>();
        for (Versioned item : request.devices()) {
            if (versions.put(item.id(), item.version()) != null) throw bad("批量选择中有重复设备");
        }
        List<Locked> rows = lockAll(versions.keySet().stream().collect(Collectors.toList()));
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
    public BatchResult importDevices(Authentication auth, @RequestParam("file") MultipartFile file) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DEVICE_IMPORT);
        scope.actor().require(Permission.FIRE_DEVICE_ADD);
        Map<String, Long> units = scope.visibleUnits().stream().collect(Collectors.toMap(FireAccess.Unit::code, FireAccess.Unit::id));
        List<FireDeviceExcel.Imported> requests = excel.read(file, units);
        for (FireDeviceExcel.Imported row : requests) {
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
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_DEVICE_READ);
        LocalDate today = LocalDate.now(BusinessTime.ZONE);
        Filter filter = filter(scope, search, today);
        workbook(response, "消防设备台账.xlsx");
        excel.writeExport(response.getOutputStream(), after -> jdbc.limit(SELECT + filter.where()
                        + " AND d.id > :after ORDER BY d.id", 500)
                .params(filter.values()).param("after", after).query(Device.class).list(), today);
    }

    private long add(FireAccess.Scope scope, Input request) {
        scope.requireUnit(request.unitId());
        validateDates(request.manufacturedOn(), request.inspectedOn(), request.expiresOn(), "inspectedOn");
        long id = fields(jdbc.sql("INSERT INTO fm_fire_device\n"
                + "    (id, unit_id, code, category, item_name, model_type, specification, use_site, storage_location,\n"
                + "     manufactured_on, inspected_on, expires_on, created_by, created_role)\n"
                + "VALUES (:newId, :unit, :code, :category, :name, :type, :spec, :site, :location,\n"
                + "        :manufactured, :inspection, :expiry, :actor, :role)\n"), request).param("unit", request.unitId()).param("inspection", request.inspectedOn())
                .param("actor", scope.actor().id()).param("role", String.join(",", scope.actor().roles()
                        .stream().filter(code -> code.startsWith("FIRE_") || code.equals(AccessService.ADMIN_ROLE)).collect(Collectors.toList()))).insert();
        event(id, scope, "CREATE", null, STOCK, request.inspectedOn(), null, null);
        return id;
    }

    private static JdbcClient.StatementSpec fields(JdbcClient.StatementSpec stmt, Input input) {
        return stmt.param("code", strip(input.code())).param("category", input.category().name())
                .param("name", strip(input.itemName())).param("type", stripToNull(input.modelType()))
                .param("spec", stripToNull(input.specification())).param("site", stripToNull(input.useSite()))
                .param("location", strip(input.storageLocation())).param("manufactured", input.manufacturedOn())
                .param("expiry", input.expiresOn());
    }

    private void apply(FireAccess.Scope scope, Locked device, Action action, LocalDate inspection, String site,
                       String storage, String remark) {
        int target = device.state();
        switch (action) {
            case MOVE:
                updatePlace(device.id(), target, device.useSite(),
                        required(storage, "storageLocation", "请输入存放地点"));
                break;
            case INSPECT: {
                if (inspection == null) throw invalidField("inspectedOn", "请填写本次年检日期");
                validateDates(device.manufacturedOn(), inspection, null, "inspectedOn");
                LocalDate latest = device.inspectedOn() == null || inspection.isAfter(device.inspectedOn())
                        ? inspection : device.inspectedOn();
                jdbc.sql("UPDATE fm_fire_device SET updated_at = LOCALTIMESTAMP, inspected_on = :date, version = version + 1 WHERE id = :id")
                        .param("date", latest).param("id", device.id()).update();
                break;
            }
            case ISSUE: {
                if (device.state() != STOCK) throw conflict("当前设备不在库存");
                target = IN_USE;
                updatePlace(device.id(), target, required(site, "useSite", "请输入使用场所"),
                        stripToNull(storage) == null ? device.storageLocation() : strip(storage));
                break;
            }
            case SCRAP: {
                target = SCRAPPED;
                updatePlace(device.id(), target, device.useSite(), device.storageLocation());
                break;
            }
            case REPLACE:
                throw bad("更换设备必须选择替换设备");
        }
        event(device.id(), scope, action.name(), device.state(), target,
                action == Action.INSPECT ? inspection : null, null, stripToNull(remark));
    }

    private void replace(FireAccess.Scope scope, long id, Operation request) {
        if (request.replacementId() == null || request.replacementId() == id) {
            throw invalidField("replacementId", "请选择替换设备");
        }
        List<Locked> pair = lockAll(Collections.unmodifiableList(Arrays.asList(id, request.replacementId())));
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
                stripToNull(request.storageLocation()) == null ? old.storageLocation() : strip(request.storageLocation()));
        updatePlace(next.id(), IN_USE, old.useSite(), old.storageLocation());
        event(old.id(), scope, "REPLACE_OUT", IN_USE, STOCK, null, next.id(), stripToNull(request.remark()));
        event(next.id(), scope, "REPLACE_IN", STOCK, IN_USE, null, old.id(), stripToNull(request.remark()));
    }

    private void updatePlace(long id, int state, String site, String location) {
        jdbc.sql("UPDATE fm_fire_device SET updated_at = LOCALTIMESTAMP, xfqc_bs = :state, use_site = :site, storage_location = :location,\n"
                + "    version = version + 1 WHERE id = :id\n")
                .param("state", state).param("site", site).param("location", location).param("id", id).update();
    }

    private void event(long id, FireAccess.Scope scope, String operation, Integer from, int to,
                       LocalDate inspection, Long related, String remark) {
        jdbc.sql("INSERT INTO fm_fire_device_event\n"
                + "    (id, device_id, actor_id, operation, from_state, to_state, inspected_on, related_device_id, remark)\n"
                + "VALUES (fm_id_seq.NEXTVAL, :device, :actor, :operation, :previous, :next, :inspection, :related, :remark)\n")
                .param("device", id).param("actor", scope.actor().id()).param("operation", operation)
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
        return jdbc.sql(SELECT + " WHERE d.id = :id AND d.deleted = 0").param("id", id).query(Device.class)
                .optional().orElseThrow(() -> missing("设备不存在"));
    }

    private FireAccess.Scope authorized(Authentication auth, Permission permission) {
        FireAccess.Scope scope = fire.scope(auth);
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

    private static String required(String value, String field, String message) {
        String result = stripToNull(value);
        if (result == null) throw invalidField(field, message);
        return result;
    }

    private static void validateDates(LocalDate manufactured, LocalDate inspected, LocalDate expires, String inspectionField) {
        if (manufactured != null && expires != null && expires.isBefore(manufactured)) {
            throw invalidField("expiresOn", "保质期截止日期不能早于出厂日期");
        }
        if (inspected != null && (inspected.isAfter(LocalDate.now(BusinessTime.ZONE))
                || manufactured != null && inspected.isBefore(manufactured))) {
            throw invalidField(inspectionField, "年检日期应在出厂之后且不能晚于今天");
        }
    }

    private static DeviceView view(FireAccess.Scope scope, Device row, LocalDate today) {
        List<Integer> notices = FireReminder.forDevice(row.state(), row.expiresOn(), row.inspectedOn(), today)
                .stream().map(FireReminder.Notice::code).collect(Collectors.toList());
        return new DeviceView(row, notices, scope.fullDeviceEdit(row.unitId()), scope.actor().has(Permission.FIRE_DEVICE_OPERATE));
    }

    private static Filter filter(FireAccess.Scope scope, Search search, LocalDate today) {
        JdbcClient.IdList units = new JdbcClient.IdList(scope.visibleIds(search.unitId()));
        StringBuilder sql = new StringBuilder(" WHERE d.deleted = 0 AND ").append(units.matches("d.unit_id"));
        Map<String, Object> params = new HashMap<>(units.parameters());
        if (search.state() != null) {
            if (search.state() < 0 || search.state() > 2) throw bad("设备状态无效");
            sql.append(" AND d.xfqc_bs = :state");
            params.put("state", search.state());
        }
        if (search.category() != null) {
            sql.append(" AND d.category = :category");
            params.put("category", search.category().name());
        }
        if (stripToNull(search.keyword()) != null) {
            if (search.keyword().length() > 120) throw bad("搜索词过长");
            sql.append(" AND (LOWER(d.code) LIKE LOWER(:keyword) OR LOWER(d.item_name) LIKE LOWER(:keyword) OR LOWER(d.storage_location) LIKE LOWER(:keyword) OR LOWER(d.use_site) LIKE LOWER(:keyword))");
            params.put("keyword", "%" + strip(search.keyword()) + "%");
        }
        if (search.notice() != null) {
            switch (search.notice()) {
                case 2:
                    sql.append(" AND d.xfqc_bs IN (1, 2) AND d.expires_on > :m3 AND d.expires_on <= :m6");
                    break;
                case 3:
                    sql.append(" AND d.xfqc_bs IN (1, 2) AND d.expires_on >= :today AND d.expires_on <= :m3");
                    break;
                case 9:
                    sql.append(" AND d.xfqc_bs IN (1, 2) AND d.expires_on < :today");
                    break;
                case 5:
                    sql.append(" AND d.xfqc_bs = 1 AND (d.inspected_on < :yearStart OR d.inspected_on >= :nextYear)");
                    break;
                case 6:
                    sql.append(" AND d.xfqc_bs = 1 AND d.inspected_on IS NULL");
                    break;
                default:
                    throw bad("提示类型无效");
            }
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

    enum Action { MOVE, INSPECT, ISSUE, REPLACE, SCRAP }
    @Value
    static class Search {
        Long unitId;
        DeviceCategory category;
        Integer state;
        Integer notice;
        String keyword;
    }
    @Value
    static class Filter {
        String where;
        Map<String, Object> values;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Input {
        @NotNull @Positive Long unitId;
        @NotBlank @Size(max = 64) String code;
        @NotNull DeviceCategory category;
        @NotBlank @Size(max = 100) String itemName;
        @Size(max = 100) String modelType;
        @Size(max = 100) String specification;
        @Size(max = 180) String useSite;
        @NotBlank @Size(max = 180) String storageLocation;
        LocalDate manufacturedOn;
        LocalDate inspectedOn;
        LocalDate expiresOn;
        @Min(0) Integer version;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Operation {
        @NotNull Action action;
        @NotNull @Min(0) Integer version;
        LocalDate inspectedOn;
        @Size(max = 180) String useSite;
        @Size(max = 180) String storageLocation;
        @Positive Long replacementId;
        @Size(max = 500) String remark;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Versioned {
        @NotNull @Positive Long id;
        @NotNull @Min(0) Integer version;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Batch {
        @NotNull Action action;
        @NotEmpty @Size(max = 500) List<@NotNull @Valid Versioned> devices;
        LocalDate inspectedOn;
        @Size(max = 500) String remark;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class BatchResult {
        int count;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Device {
        long id;
        long unitId;
        String unitName;
        String unitCode;
        String code;
        DeviceCategory category;
        String itemName;
        String modelType;
        String specification;
        String useSite;
        String storageLocation;
        LocalDate manufacturedOn;
        LocalDate inspectedOn;
        LocalDate expiresOn;
        int state;
        int version;
        String createdRole;
        LocalDateTime createdAt;
        LocalDateTime updatedAt;
    }
    @Value
    static class Locked {
        long id;
        long unitId;
        DeviceCategory category;
        int state;
        int version;
        String useSite;
        String storageLocation;
        LocalDate manufacturedOn;
        LocalDate inspectedOn;
        boolean deleted;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class DeviceView {
        Device device;
        List<Integer> notices;
        boolean fullEdit;
        boolean operate;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Event {
        String operation;
        Integer fromState;
        int toState;
        LocalDate inspectedOn;
        String remark;
        LocalDateTime createdAt;
        String actorName;
        String relatedCode;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class MaintainerSummary {
        long id;
        String name;
        String contact;
        String phone;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Detail {
        DeviceView item;
        List<Event> history;
        List<MaintainerSummary> maintainers;
    }
}
