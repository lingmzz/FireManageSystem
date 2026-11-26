package com.firemanage;

import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import static com.firemanage.ApiSupport.*;

@RestController
@RequestMapping("/api/fire/maintainers")
class FireMaintainerController {
    private static final String FILTER = """
            FROM fm_fire_maintainer m WHERE NOT m.deleted AND m.name LIKE :keyword
            AND EXISTS (
                SELECT 1 FROM fm_fire_maintainer_unit mu
                WHERE mu.maintainer_id = m.id AND mu.unit_id IN (:units)
            )
            """;
    private final FireAccess fire;
    private final JdbcClient jdbc;

    FireMaintainerController(FireAccess fire, JdbcClient jdbc) {
        this.fire = fire;
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    Page<Detail> list(Authentication auth, @RequestParam(required = false) Long unitId,
                      @RequestParam(defaultValue = "") String keyword,
                      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
        var scope = authorized(auth, Permission.FIRE_MAINT_READ);
        var paging = new Paging(page, size);
        var ids = scope.visibleIds(unitId);
        var query = "%" + keyword.strip() + "%";
        long total = jdbc.sql("SELECT COUNT(*) " + FILTER).param("keyword", query)
                .param("units", ids).query(Long.class).single();
        var vendors = jdbc.sql("SELECT m.id, m.name, m.contact, m.phone, m.version " + FILTER
                        + " ORDER BY m.id DESC LIMIT :limit OFFSET :skip")
                .param("keyword", query).param("units", ids).param("limit", size).param("skip", paging.offset())
                .query(Maintainer.class).list();
        return paging.result(vendors.stream().map(vendor -> detail(scope, vendor)).toList(), total);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    AdminController.Id create(Authentication auth, @Valid @RequestBody Form form) {
        var scope = authorized(auth, Permission.FIRE_MAINT_WRITE);
        validateLinks(scope, form);
        long id = insert(values(jdbc.sql("""
                INSERT INTO fm_fire_maintainer (name, contact, phone) VALUES (:name, :contact, :phone)
                """), form));
        links(id, form);
        return new AdminController.Id(id);
    }

    @PutMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(Authentication auth, @PathVariable long id, @Valid @RequestBody Form form) {
        var scope = authorized(auth, Permission.FIRE_MAINT_WRITE);
        var current = lockForEdit(scope, id);
        if (form.version() == null || form.version() != current.version()) throw conflict("维保单位资料已更新");
        validateLinks(scope, form);
        values(jdbc.sql("""
                UPDATE fm_fire_maintainer SET name = :name, contact = :contact, phone = :phone,
                    version = version + 1 WHERE id = :id
                """), form).param("id", id).update();
        jdbc.sql("DELETE FROM fm_fire_maintainer_device WHERE maintainer_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM fm_fire_maintainer_unit WHERE maintainer_id = :id").param("id", id).update();
        links(id, form);
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(Authentication auth, @PathVariable long id) {
        lockForEdit(authorized(auth, Permission.FIRE_MAINT_WRITE), id);
        jdbc.sql("UPDATE fm_fire_maintainer SET deleted = TRUE, version = version + 1 WHERE id = :id").param("id", id).update();
    }

    private Detail detail(FireAccess.Scope scope, Maintainer vendor) {
        var ids = scope.visibleIds(null);
        var units = jdbc.sql("""
                SELECT u.id, u.code, u.name FROM fm_fire_maintainer_unit l JOIN fm_fire_unit u ON u.id = l.unit_id
                WHERE l.maintainer_id = :id AND u.id IN (:units) ORDER BY u.name
                """).param("id", vendor.id()).param("units", ids).query(UnitRef.class).list();
        var devices = jdbc.sql("""
                SELECT d.id, d.code, d.item_name, d.unit_id, u.name AS unit_name
                FROM fm_fire_maintainer_device l JOIN fm_fire_device d ON d.id = l.device_id
                JOIN fm_fire_unit u ON u.id = d.unit_id
                WHERE l.maintainer_id = :id AND d.unit_id IN (:units) AND NOT d.deleted ORDER BY d.code
                """).param("id", vendor.id()).param("units", ids).query(DeviceRef.class).list();
        boolean editable = scope.actor().has(Permission.FIRE_MAINT_WRITE) && covered(scope, vendor.id());
        return new Detail(vendor, units, devices, editable);
    }

    private Maintainer lockForEdit(FireAccess.Scope scope, long id) {
        var current = jdbc.sql("""
                SELECT id, name, contact, phone, version FROM fm_fire_maintainer WHERE id = :id AND NOT deleted FOR UPDATE
                """).param("id", id).query(Maintainer.class).optional().orElseThrow(() -> missing("维保单位不存在"));
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
        var devices = jdbc.sql("SELECT id, unit_id FROM fm_fire_device WHERE id IN (:ids) AND NOT deleted")
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
        return stmt.param("name", form.name().strip()).param("contact", form.contact().strip())
                .param("phone", form.phone().strip());
    }

    private FireAccess.Scope authorized(Authentication auth, Permission permission) {
        var scope = fire.scope(auth);
        scope.actor().require(permission);
        return scope;
    }

    record Maintainer(long id, String name, String contact, String phone, int version) {}
    record UnitRef(long id, String code, String name) {}
    record DeviceUnit(long id, long unitId) {}
    record DeviceRef(long id, String code, String itemName, long unitId, String unitName) {}
    record Detail(Maintainer maintainer, List<UnitRef> units, List<DeviceRef> devices, boolean editable) {}
    record Form(@NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 60) String contact,
                @NotBlank @Size(max = 40) String phone, @NotEmpty @Size(max = 250) Set<@NotNull @Positive Long> unitIds,
                @NotNull @Size(max = 5000) Set<@NotNull @Positive Long> deviceIds, @Min(0) Integer version) {}
}
