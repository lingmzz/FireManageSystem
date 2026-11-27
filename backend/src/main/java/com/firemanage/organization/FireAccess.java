package com.firemanage.organization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.firemanage.common.JdbcClient;
import com.firemanage.identity.AccessService;
import com.firemanage.identity.Permission;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import static com.firemanage.common.ApiSupport.bad;
import static com.firemanage.common.ApiSupport.forbidden;

@Service
public class FireAccess {
    private final JdbcClient jdbc;
    private final AccessService access;

    FireAccess(JdbcClient jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public Scope scope(Authentication authentication) {
        return scope(access.actor(authentication));
    }

    public Scope scope(AccessService.Actor actor) {
        LinkedHashMap<Long, Unit> directory = new LinkedHashMap<>();
        for (Unit unit : units()) {
            directory.put(unit.id(), unit);
        }
        Set<Long> bindings = bindings(actor.id()).stream()
                .filter(id -> {
                    Unit unit = directory.get(id);
                    return unit != null && actor.roles().contains(unit.kind().role);
                })
                .collect(Collectors.collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet));
        return new Scope(actor, directory, bindings);
    }

    public List<Unit> units() {
        return jdbc.sql("SELECT id, code, name, kind, parent_id FROM fm_fire_unit\n"
                + "ORDER BY CASE kind WHEN 'SEGMENT' THEN 1 WHEN 'DEPARTMENT' THEN 2 ELSE 3 END, name, id\n").query(Unit.class).list();
    }

    List<Long> bindings(long userId) {
        return jdbc.sql("SELECT unit_id FROM fm_fire_user_unit WHERE user_id = :user ORDER BY unit_id")
                .param("user", userId).query(Long.class).list();
    }

    public enum Kind {
        SEGMENT("FIRE_SEGMENT"), DEPARTMENT("FIRE_DEPARTMENT"), STATION("FIRE_STATION");

        final String role;

        Kind(String role) {
            this.role = role;
        }
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Unit {
        long id;
        String code;
        String name;
        Kind kind;
        Long parentId;
    }

    @Value
    public static class Scope {
        AccessService.Actor actor;
        Map<Long, Unit> directory;
        Set<Long> bindings;

        public Unit requireUnit(long id) {
            Unit unit = directory.get(id);
            if (unit == null) {
                throw bad("所属消防单位不存在");
            }
            if (!canSee(id)) {
                throw forbidden();
            }
            return unit;
        }

        public boolean canSee(long id) {
            return hasCoverage(id, null);
        }

        public boolean hasCoverage(long id, Kind kind) {
            if (actor.admin()) {
                return directory.containsKey(id);
            }
            for (Unit unit = directory.get(id); unit != null; unit = directory.get(unit.parentId())) {
                if (bindings.contains(unit.id()) && (kind == null || unit.kind() == kind)) {
                    return true;
                }
            }
            return false;
        }

        public boolean owns(long id) {
            return directory.containsKey(id) && (actor.admin() || bindings.contains(id));
        }

        public boolean fullDeviceEdit(long id) {
            return actor.has(Permission.FIRE_DEVICE_EDIT)
                    && (hasCoverage(id, Kind.SEGMENT) || hasCoverage(id, Kind.DEPARTMENT));
        }

        public List<Unit> visibleUnits() {
            return directory.values().stream().filter(unit -> canSee(unit.id())).collect(Collectors.toList());
        }

        public List<Long> visibleIds(Long unitId) {
            if (unitId != null) {
                requireUnit(unitId);
            }
            List<Long> ids = visibleUnits().stream()
                    .filter(unit -> unitId == null || within(unit, unitId))
                    .map(Unit::id).collect(Collectors.toList());
            return ids.isEmpty() ? Collections.singletonList(-1L) : ids;
        }

        private boolean within(Unit unit, long ancestorId) {
            for (Unit cursor = unit; cursor != null; cursor = directory.get(cursor.parentId())) {
                if (cursor.id() == ancestorId) {
                    return true;
                }
            }
            return false;
        }
    }
}
