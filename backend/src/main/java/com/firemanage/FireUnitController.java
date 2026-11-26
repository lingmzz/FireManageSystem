package com.firemanage;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.firemanage.ApiSupport.duplicateField;
import static com.firemanage.ApiSupport.invalidField;
import static com.firemanage.ApiSupport.missing;
import static org.apache.commons.lang3.StringUtils.strip;

@RestController
@RequestMapping("/api/fire/units")
class FireUnitController {
    private final JdbcClient jdbc;
    private final FireAccess fire;

    FireUnitController(JdbcClient jdbc, FireAccess fire) {
        this.jdbc = jdbc;
        this.fire = fire;
    }

    @GetMapping
    List<FireAccess.Unit> list(Authentication authentication) {
        return fire.scope(authentication).visibleUnits();
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    AdminController.Id create(Authentication auth, @Valid @RequestBody UnitForm form) {
        fire.scope(auth).actor().require(Permission.FIRE_ORG);
        validateParent(form);
        try {
            return new AdminController.Id(assign(jdbc.sql("INSERT INTO fm_fire_unit (id, code, name, kind, parent_id)\n"
                    + "VALUES (:newId, :code, :name, :kind, :parentId)\n"), form).insert());
        } catch (DuplicateKeyException failure) {
            throw duplicateField("code", "该单位编号已存在");
        }
    }

    @PutMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(Authentication auth, @PathVariable long id, @Valid @RequestBody UnitForm form) {
        fire.scope(auth).actor().require(Permission.FIRE_ORG);
        FireAccess.Kind kind = unit(id).kind();
        if (kind != form.kind()) {
            throw invalidField("kind", "已建立的单位不能改为其它层级");
        }
        validateParent(form);
        try {
            assign(jdbc.sql("UPDATE fm_fire_unit SET code = :code, name = :name, kind = :kind, parent_id = :parentId\n"
                    + "WHERE id = :id\n"), form).param("id", id).update();
        } catch (DuplicateKeyException failure) {
            throw duplicateField("code", "该单位编号已存在");
        }
    }

    @GetMapping("/users")
    List<UserOption> users(Authentication auth, @RequestParam(defaultValue = "") String keyword) {
        fire.scope(auth).actor().require(Permission.FIRE_ORG);
        return jdbc.limit("SELECT id, username, display_name FROM fm_user\n"
                + "WHERE LOWER(username) LIKE LOWER(:keyword) OR LOWER(display_name) LIKE LOWER(:keyword) ORDER BY id", 100)
                .param("keyword", "%" + strip(keyword) + "%").query(UserOption.class).list();
    }

    @GetMapping("/users/{id}")
    Binding binding(Authentication auth, @PathVariable long id) {
        fire.scope(auth).actor().require(Permission.FIRE_ORG);
        List<String> roles = userRoles(id);
        List<Long> eligible = fire.units().stream().filter(unit -> roles.contains(unit.kind().role)).map(FireAccess.Unit::id).collect(Collectors.toList());
        return new Binding(id, roles, fire.bindings(id).stream().filter(eligible::contains).collect(Collectors.toList()));
    }

    @PutMapping("/users/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void bind(Authentication auth, @PathVariable long id, @Valid @RequestBody UnitBindings request) {
        fire.scope(auth).actor().require(Permission.FIRE_ORG);
        jdbc.sql("SELECT id FROM fm_user WHERE id = :id FOR UPDATE").param("id", id).query(Long.class)
                .optional().orElseThrow(() -> missing("用户不存在"));
        List<String> roles = userRoles(id);
        for (long unitId : request.unitIds()) {
            if (!roles.contains(unit(unitId).kind().role)) {
                throw invalidField("unitIds", "授权单位层级必须与用户消防角色一致");
            }
        }
        jdbc.sql("DELETE FROM fm_fire_user_unit WHERE user_id = :user").param("user", id).update();
        for (long unitId : request.unitIds()) {
            jdbc.sql("INSERT INTO fm_fire_user_unit (user_id, unit_id) VALUES (:user, :unit)")
                    .param("user", id).param("unit", unitId).update();
        }
    }

    private List<String> userRoles(long id) {
        jdbc.sql("SELECT id FROM fm_user WHERE id = :id").param("id", id).query(Long.class)
                .optional().orElseThrow(() -> missing("用户不存在"));
        return jdbc.sql("SELECT r.code FROM fm_user_role ur JOIN fm_role r ON r.id = ur.role_id\n"
                + "WHERE ur.user_id = :user ORDER BY r.code\n").param("user", id).query(String.class).list();
    }

    private FireAccess.Unit unit(long id) {
        return jdbc.sql("SELECT id, code, name, kind, parent_id FROM fm_fire_unit WHERE id = :id")
                .param("id", id).query(FireAccess.Unit.class).optional().orElseThrow(() -> missing("消防单位不存在"));
    }

    private void validateParent(UnitForm form) {
        FireAccess.Kind expected;
        switch (form.kind()) {
            case SEGMENT:
                expected = null;
                break;
            case DEPARTMENT:
                expected = FireAccess.Kind.SEGMENT;
                break;
            case STATION:
                expected = FireAccess.Kind.DEPARTMENT;
                break;
            default:
                throw new AssertionError(form.kind());
        }
        if (expected == null ? form.parentId() != null : form.parentId() == null || unit(form.parentId()).kind() != expected) {
            throw invalidField("parentId", "单位层级应按车务段、科室、车站设置");
        }
    }

    private static JdbcClient.StatementSpec assign(JdbcClient.StatementSpec stmt, UnitForm form) {
        return stmt.param("code", strip(form.code()).toUpperCase(Locale.ROOT))
                .param("name", strip(form.name())).param("kind", form.kind().name()).param("parentId", form.parentId());
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class UnitForm {
        @NotBlank @Size(max = 40) String code;
        @NotBlank @Size(max = 100) String name;
        @NotNull FireAccess.Kind kind;
        @Positive Long parentId;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class UserOption {
        long id;
        String username;
        String displayName;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Binding {
        long userId;
        List<String> roles;
        List<Long> unitIds;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class UnitBindings {
        @NotNull @Size(max = 250) Set<@NotNull @Positive Long> unitIds;
    }
}
