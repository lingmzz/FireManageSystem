package com.firemanage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import static com.firemanage.ApiSupport.bad;
import static com.firemanage.ApiSupport.forbidden;

@Service
class FireAccess {
    private final JdbcClient jdbc;
    private final AccessService access;

    FireAccess(JdbcClient jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    Scope scope(Authentication authentication) {
        return scope(access.actor(authentication));
    }

    Scope scope(AccessService.Actor actor) {
        var directory = new LinkedHashMap<Long, Unit>();
        for (Unit unit : units()) {
            directory.put(unit.id(), unit);
        }
        Set<Long> bindings = bindings(actor.id()).stream()
                .filter(id -> {
                    Unit unit = directory.get(id);
                    return unit != null && actor.roles().contains(unit.kind().role);
                })
                .collect(Collectors.toUnmodifiableSet());
        return new Scope(actor, directory, bindings);
    }

    List<Unit> units() {
        return jdbc.sql("""
                SELECT id, code, name, kind, parent_id FROM fm_fire_unit
                ORDER BY CASE kind WHEN 'SEGMENT' THEN 1 WHEN 'DEPARTMENT' THEN 2 ELSE 3 END, name, id
                """).query(Unit.class).list();
    }

    List<Long> bindings(long userId) {
        return jdbc.sql("SELECT unit_id FROM fm_fire_user_unit WHERE user_id = :user ORDER BY unit_id")
                .param("user", userId).query(Long.class).list();
    }

    enum Kind {
        SEGMENT("FIRE_SEGMENT"), DEPARTMENT("FIRE_DEPARTMENT"), STATION("FIRE_STATION");

        final String role;

        Kind(String role) {
            this.role = role;
        }
    }

    record Unit(long id, String code, String name, Kind kind, Long parentId) {}

    record Scope(AccessService.Actor actor, Map<Long, Unit> directory, Set<Long> bindings) {
        Unit requireUnit(long id) {
            Unit unit = directory.get(id);
            if (unit == null) {
                throw bad("所属消防单位不存在");
            }
            if (!canSee(id)) {
                throw forbidden();
            }
            return unit;
        }

        boolean canSee(long id) {
            return hasCoverage(id, null);
        }

        boolean hasCoverage(long id, Kind kind) {
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

        boolean owns(long id) {
            return directory.containsKey(id) && (actor.admin() || bindings.contains(id));
        }

        boolean fullDeviceEdit(long id) {
            return actor.has(Permission.FIRE_DEVICE_EDIT)
                    && (hasCoverage(id, Kind.SEGMENT) || hasCoverage(id, Kind.DEPARTMENT));
        }

        List<Unit> visibleUnits() {
            return directory.values().stream().filter(unit -> canSee(unit.id())).toList();
        }

        List<Long> visibleIds(Long unitId) {
            if (unitId != null) {
                requireUnit(unitId);
            }
            var ids = visibleUnits().stream()
                    .filter(unit -> unitId == null || within(unit, unitId))
                    .map(Unit::id).toList();
            return ids.isEmpty() ? List.of(-1L) : ids;
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
