package com.firemanage.maintenance;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
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

import static com.firemanage.common.ApiSupport.*;
import static org.apache.commons.lang3.StringUtils.strip;

@RestController
@RequestMapping("/api/fire/maintainers")
class FireMaintainerController {
    private final FireAccess fire;
    private final JdbcClient jdbc;

    FireMaintainerController(FireAccess fire, JdbcClient jdbc) {
        this.fire = fire;
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<Detail> list(Authentication auth, @RequestParam(required = false) Long unitId,
                      @RequestParam(defaultValue = "") String keyword,
                      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_MAINT_READ);
        ApiSupport.Paging paging = new Paging(page, size);
        JdbcClient.IdList ids = new JdbcClient.IdList(scope.visibleIds(unitId));
        String filter = "FROM fm_fire_maintainer m WHERE m.deleted = 0 AND LOWER(m.name) LIKE LOWER(:keyword)\n"
                + "AND EXISTS (SELECT 1 FROM fm_fire_maintainer_unit mu WHERE mu.maintainer_id = m.id AND "
                + ids.matches("mu.unit_id") + ")";
        String query = "%" + strip(keyword) + "%";
        long total = jdbc.sql("SELECT COUNT(*) " + filter).param("keyword", query)
                .params(ids.parameters()).query(Long.class).single();
        List<Maintainer> vendors = jdbc.page("SELECT m.id, m.name, m.contact, m.phone, m.version " + filter
                        + " ORDER BY m.id DESC", paging.offset(), paging.size())
                .param("keyword", query).params(ids.parameters())
                .query(Maintainer.class).list();
        return paging.result(vendors.stream().map(vendor -> detail(scope, vendor)).collect(Collectors.toList()), total);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedId create(Authentication auth, @Valid @RequestBody Form form) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_MAINT_WRITE);
        validateLinks(scope, form);
        long id = values(jdbc.sql("INSERT INTO fm_fire_maintainer (id, name, contact, phone) VALUES (:newId, :name, :contact, :phone)\n"), form).insert();
        links(id, form);
        return new CreatedId(id);
    }

    @PutMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(Authentication auth, @PathVariable long id, @Valid @RequestBody Form form) {
        FireAccess.Scope scope = authorized(auth, Permission.FIRE_MAINT_WRITE);
        Maintainer current = lockForEdit(scope, id);
        if (form.version() == null || form.version() != current.version()) throw conflict("维保单位资料已更新");
        validateLinks(scope, form);
        values(jdbc.sql("UPDATE fm_fire_maintainer SET name = :name, contact = :contact, phone = :phone,\n"
                + "    version = version + 1 WHERE id = :id\n"), form).param("id", id).update();
        jdbc.sql("DELETE FROM fm_fire_maintainer_device WHERE maintainer_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM fm_fire_maintainer_unit WHERE maintainer_id = :id").param("id", id).update();
        links(id, form);
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication auth, @PathVariable long id) {
        lockForEdit(authorized(auth, Permission.FIRE_MAINT_WRITE), id);
        jdbc.sql("UPDATE fm_fire_maintainer SET deleted = 1, version = version + 1 WHERE id = :id").param("id", id).update();
    }

    private Detail detail(FireAccess.Scope scope, Maintainer vendor) {
        JdbcClient.IdList ids = new JdbcClient.IdList(scope.visibleIds(null));
        List<UnitRef> units = jdbc.sql("SELECT u.id, u.code, u.name FROM fm_fire_maintainer_unit l JOIN fm_fire_unit u ON u.id = l.unit_id\n"
                + "WHERE l.maintainer_id = :id AND " + ids.matches("u.id") + " ORDER BY u.name")
                .param("id", vendor.id()).params(ids.parameters()).query(UnitRef.class).list();
        List<DeviceRef> devices = jdbc.sql("SELECT d.id, d.code, d.item_name, d.unit_id, u.name AS unit_name\n"
                + "FROM fm_fire_maintainer_device l JOIN fm_fire_device d ON d.id = l.device_id\n"
                + "JOIN fm_fire_unit u ON u.id = d.unit_id\n"
                + "WHERE l.maintainer_id = :id AND " + ids.matches("d.unit_id") + " AND d.deleted = 0 ORDER BY d.code")
                .param("id", vendor.id()).params(ids.parameters()).query(DeviceRef.class).list();
        boolean editable = scope.actor().has(Permission.FIRE_MAINT_WRITE) && covered(scope, vendor.id());
        return new Detail(vendor, units, devices, editable);
    }

    private Maintainer lockForEdit(FireAccess.Scope scope, long id) {
        Maintainer current = jdbc.sql("SELECT id, name, contact, phone, version FROM fm_fire_maintainer WHERE id = :id AND deleted = 0 FOR UPDATE\n")
                .param("id", id).query(Maintainer.class).optional()
                .orElseThrow(() -> missing("维保单位不存在"));
        if (!covered(scope, id)) throw forbidden();
        return current;
    }

    private boolean covered(FireAccess.Scope scope, long id) {
        return jdbc.sql("SELECT unit_id FROM fm_fire_maintainer_unit WHERE maintainer_id = :id")
                .param("id", id).query(Long.class).list().stream()
                .allMatch(unit -> scope.hasCoverage(unit, FireAccess.Kind.SEGMENT));
    }

    private void validateLinks(FireAccess.Scope scope, Form form) {
        for (long unit : form.unitIds()) {
            if (!scope.hasCoverage(unit, FireAccess.Kind.SEGMENT)) throw forbidden();
        }
        if (form.deviceIds().isEmpty()) return;
        List<DeviceUnit> devices = jdbc.sql("SELECT id, unit_id FROM fm_fire_device WHERE id IN (:ids) AND deleted = 0")
                .param("ids", form.deviceIds()).query(DeviceUnit.class).list();
        if (devices.size() != form.deviceIds().size() || devices.stream().anyMatch(d -> !form.unitIds().contains(d.unitId()))) {
            throw invalidField("deviceIds", "关联设备必须属于选择的维保范围");
        }
    }

    private void links(long vendor, Form form) {
        for (long unit : form.unitIds()) {
            jdbc.sql("INSERT INTO fm_fire_maintainer_unit (maintainer_id, unit_id) VALUES (:vendor, :unit)")
                    .param("vendor", vendor).param("unit", unit).update();
        }
        for (long device : form.deviceIds()) {
            jdbc.sql("INSERT INTO fm_fire_maintainer_device (maintainer_id, device_id) VALUES (:vendor, :device)")
                    .param("vendor", vendor).param("device", device).update();
        }
    }

    private static JdbcClient.StatementSpec values(JdbcClient.StatementSpec stmt, Form form) {
        return stmt.param("name", strip(form.name())).param("contact", strip(form.contact()))
                .param("phone", strip(form.phone()));
    }

    private FireAccess.Scope authorized(Authentication auth, Permission permission) {
        FireAccess.Scope scope = fire.scope(auth);
        scope.actor().require(permission);
        return scope;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Maintainer {
        long id;
        String name;
        String contact;
        String phone;
        int version;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class UnitRef {
        long id;
        String code;
        String name;
    }
    @Value
    static class DeviceUnit {
        long id;
        long unitId;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class DeviceRef {
        long id;
        String code;
        String itemName;
        long unitId;
        String unitName;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Detail {
        Maintainer maintainer;
        List<UnitRef> units;
        List<DeviceRef> devices;
        boolean editable;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Form {
        @NotBlank @Size(max = 160) String name;
        @NotBlank @Size(max = 60) String contact;
        @NotBlank @Size(max = 40) String phone;
        @NotEmpty @Size(max = 250) Set<@NotNull @Positive Long> unitIds;
        @NotNull @Size(max = 5000) Set<@NotNull @Positive Long> deviceIds;
        @Min(0) Integer version;
    }
}
