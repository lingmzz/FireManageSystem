package com.firemanage;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.firemanage.ApiSupport.bad;
import static com.firemanage.ApiSupport.duplicateField;
import static com.firemanage.ApiSupport.forbidden;
import static com.firemanage.ApiSupport.insert;
import static com.firemanage.ApiSupport.invalidField;
import static com.firemanage.ApiSupport.missing;

@RestController
@RequestMapping("/api")
class AdminController {
    private static final String USER_FILTER = """
             FROM fm_user
             WHERE (:keyword = '' OR username LIKE :pattern OR display_name LIKE :pattern)
            """;
    private final JdbcClient jdbc;
    private final AccessService access;
    private final PasswordEncoder passwords;
    private final UserProfiles profiles;
    private final FireAccess fire;
    private final MenuController menus;

    AdminController(JdbcClient jdbc, AccessService access, PasswordEncoder passwords,
                    UserProfiles profiles, FireAccess fire, MenuController menus) {
        this.jdbc = jdbc;
        this.access = access;
        this.passwords = passwords;
        this.profiles = profiles;
        this.fire = fire;
        this.menus = menus;
    }

    @GetMapping("/users")
    @Transactional(readOnly = true)
    ApiSupport.Page<UserView> users(Authentication authentication,
                                     @RequestParam(defaultValue = "") String keyword,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        access.actor(authentication).require(Permission.USERS);
        var paging = new ApiSupport.Paging(page, size);
        String search = keyword.strip();
        if (search.length() > 80) {
            throw bad("搜索词过长");
        }
        String pattern = "%" + search + "%";
        long total = jdbc.sql("SELECT COUNT(*)" + USER_FILTER)
                .param("keyword", search).param("pattern", pattern).query(Long.class).single();
        var rows = jdbc.sql("SELECT id, username, display_name, enabled, created_at" + USER_FILTER
                        + " ORDER BY id DESC LIMIT :size OFFSET :offset")
                .param("keyword", search).param("pattern", pattern)
                .param("size", paging.size()).param("offset", paging.offset()).query(UserRow.class).list();
        var ids = rows.stream().map(UserRow::id).toList();
        var roles = rolesByUser(ids);
        var details = profiles.find(ids);
        return paging.result(rows.stream().map(row -> new UserView(row.id(), row.username(), row.displayName(),
                row.enabled(), row.createdAt(), roles.getOrDefault(row.id(), List.of()),
                details.getOrDefault(row.id(), UserProfiles.EMPTY))).toList(), total);
    }

    @GetMapping("/users/units")
    List<FireAccess.Unit> userUnits(Authentication authentication) {
        access.actor(authentication).require(Permission.USERS);
        return fire.units();
    }

    @GetMapping("/users/{id}/avatar")
    ResponseEntity<byte[]> avatar(Authentication authentication, @PathVariable long id) {
        var actor = access.actor(authentication);
        if (id != actor.id()) actor.require(Permission.USERS);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .body(profiles.image(id));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Id createUser(Authentication authentication, @Valid @RequestBody UserCreate request) {
        var actor = access.actor(authentication);
        actor.require(Permission.USERS);
        validateRoles(actor, request.roleIds());
        long id;
        try {
            id = insert(jdbc.sql("""
                    INSERT INTO fm_user (username, display_name, password_hash, enabled)
                    VALUES (:username, :displayName, :password, :enabled)
                    """).param("username", AccessService.normalizeUsername(request.username()))
                    .param("displayName", request.displayName().strip())
                    .param("password", encode(request.password())).param("enabled", request.enabled()));
        } catch (DuplicateKeyException failure) {
            throw duplicateField("username", "该用户名已存在");
        }
        setUserRoles(id, request.roleIds());
        saveProfile(id, request.profile());
        return new Id(id);
    }

    @PutMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void updateUser(Authentication authentication, @PathVariable long id, @Valid @RequestBody UserUpdate request) {
        var actor = access.actor(authentication);
        actor.require(Permission.USERS);
        var user = manageableUser(actor, id);
        var roles = validateRoles(actor, request.roleIds());
        if (AccessService.BOOTSTRAP_USER.equals(user.username())) {
            if (!request.enabled()) throw invalidField("enabled", "初始管理员必须保持启用");
            if (roles.stream().noneMatch(role -> AccessService.ADMIN_ROLE.equals(role.code()))) {
                throw invalidField("roleIds", "初始管理员必须保留系统管理员角色");
            }
        }
        if (actor.id() == id && !request.enabled()) {
            throw invalidField("enabled", "不能停用当前登录账号");
        }
        jdbc.sql("""
                UPDATE fm_user SET display_name = :name, enabled = :enabled,
                    auth_version = auth_version + :revision WHERE id = :id
                """).param("name", request.displayName().strip()).param("enabled", request.enabled())
                .param("revision", user.enabled() == request.enabled() ? 0 : 1).param("id", id).update();
        setUserRoles(id, request.roleIds());
        saveProfile(id, request.profile());
    }

    private void saveProfile(long id, UserProfiles.Administration profile) {
        if (profile == null) return;
        profiles.save(id, profile.personal(), "profile.personal.");
        profiles.saveUnit(id, profile.unitId());
    }

    @PutMapping("/users/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void resetPassword(Authentication authentication, @PathVariable long id, @Valid @RequestBody PasswordReset request) {
        var actor = access.actor(authentication);
        actor.require(Permission.USERS);
        manageableUser(actor, id);
        jdbc.sql("""
                UPDATE fm_user SET password_hash = :hash, auth_version = auth_version + 1 WHERE id = :id
                """).param("hash", encode(request.password())).param("id", id).update();
    }

    @GetMapping("/roles")
    @Transactional(readOnly = true)
    List<RoleView> roles(Authentication authentication) {
        var actor = access.actor(authentication);
        if (!actor.has(Permission.USERS) && !actor.has(Permission.ROLES)) {
            throw forbidden();
        }
        return jdbc.sql("SELECT id, code, name FROM fm_role ORDER BY id").query(RoleSummary.class).list()
                .stream().map(role -> new RoleView(role.id(), role.code(), role.name(),
                        rolePermissions(role).stream().sorted().toList())).toList();
    }

    @GetMapping("/roles/permissions")
    List<PermissionChoice> permissionOptions(Authentication authentication) {
        access.actor(authentication).require(Permission.ROLES);
        return Arrays.stream(Permission.values())
                .map(permission -> new PermissionChoice(permission.code, permission.label)).toList();
    }

    @GetMapping("/roles/permission-groups")
    @Transactional(readOnly = true)
    List<PermissionGroup> permissionGroups(Authentication authentication) {
        var actor = access.actor(authentication);
        actor.require(Permission.ROLES);
        var paths = menus.viewPaths();
        return Arrays.stream(MenuController.View.values()).map(view ->
                new PermissionGroup(view.key, paths.getOrDefault(view.key, List.of(view.label)),
                        view.permissions.stream().map(permission ->
                                new GrantChoice(permission.code, permission.label, actor.has(permission))).toList()))
                .toList();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Id createRole(Authentication authentication, @Valid @RequestBody RoleCreate request) {
        var actor = access.actor(authentication);
        actor.require(Permission.ROLES);
        validateGrants(actor, request.permissions());
        String code = request.code().toUpperCase(Locale.ROOT);
        if (AccessService.ADMIN_ROLE.equals(code)) {
            throw invalidField("code", "系统管理员角色使用内置编码");
        }
        long id;
        try {
            id = insert(jdbc.sql("INSERT INTO fm_role (code, name) VALUES (:code, :name)")
                    .param("code", code).param("name", request.name().strip()));
        } catch (DuplicateKeyException failure) {
            throw duplicateField("code", "该角色编码已存在");
        }
        setPermissions(id, request.permissions());
        return new Id(id);
    }

    @PutMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void updateRole(Authentication authentication, @PathVariable long id, @Valid @RequestBody RoleUpdate request) {
        var actor = access.actor(authentication);
        actor.require(Permission.ROLES);
        manageableRole(actor, id);
        validateGrants(actor, request.permissions());
        jdbc.sql("UPDATE fm_role SET name = :name WHERE id = :id")
                .param("name", request.name().strip()).param("id", id).update();
        setPermissions(id, request.permissions());
    }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    void deleteRole(Authentication authentication, @PathVariable long id) {
        var actor = access.actor(authentication);
        actor.require(Permission.ROLES);
        manageableRole(actor, id);
        long assigned = jdbc.sql("SELECT COUNT(*) FROM fm_user_role WHERE role_id = :id")
                .param("id", id).query(Long.class).single();
        if (assigned > 0) {
            throw ApiSupport.conflict("该角色仍分配给用户，请先解除分配");
        }
        boolean usedInWorkflow = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM fm_workflow_step s WHERE s.role_id = :id AND (
                        EXISTS (SELECT 1 FROM fm_workflow_config c WHERE c.active_version_id = s.version_id)
                        OR EXISTS (SELECT 1 FROM fm_approval_route run WHERE run.version_id = s.version_id AND run.step_key IS NOT NULL)
                    )
                )
                """).param("id", id).query(Boolean.class).single();
        if (usedInWorkflow) throw ApiSupport.conflict("该角色仍用于生效流程或办理中的单据");
        jdbc.sql("DELETE FROM fm_role WHERE id = :id").param("id", id).update();
    }

    private UserRow manageableUser(AccessService.Actor actor, long id) {
        var user = jdbc.sql("""
                SELECT id, username, display_name, enabled, created_at FROM fm_user WHERE id = :id FOR UPDATE
                """).param("id", id).query(UserRow.class).optional().orElseThrow(() -> missing("用户不存在"));
        if (!actor.admin()) {
            var roles = rolesByUser(List.of(id)).getOrDefault(id, List.of());
            if (roles.stream().anyMatch(role -> AccessService.ADMIN_ROLE.equals(role.code()))) {
                throw forbidden();
            }
            actor.requireGrants(access.permissionsForUser(id));
        }
        return user;
    }

    private RoleSummary manageableRole(AccessService.Actor actor, long id) {
        var role = jdbc.sql("SELECT id, code, name FROM fm_role WHERE id = :id FOR UPDATE")
                .param("id", id).query(RoleSummary.class).optional().orElseThrow(() -> missing("角色不存在"));
        if (AccessService.ADMIN_ROLE.equals(role.code())) {
            throw bad("系统管理员角色的权限由系统管理");
        }
        actor.requireGrants(rolePermissions(role));
        return role;
    }

    private Set<String> rolePermissions(RoleSummary role) {
        return AccessService.ADMIN_ROLE.equals(role.code()) ? Permission.ALL_CODES : access.permissionsForRole(role.id());
    }

    private List<RoleSummary> validateRoles(AccessService.Actor actor, Set<Long> ids) {
        var result = jdbc.sql("SELECT id, code, name FROM fm_role WHERE id IN (:ids) FOR UPDATE")
                .param("ids", ids).query(RoleSummary.class).list();
        if (result.size() != ids.size()) {
            throw invalidField("roleIds", "选择的角色已变更，请重新打开表单");
        }
        for (RoleSummary role : result) {
            if (AccessService.ADMIN_ROLE.equals(role.code()) && !actor.admin()) {
                throw forbidden();
            }
            actor.requireGrants(rolePermissions(role));
        }
        return result;
    }

    private static void validateGrants(AccessService.Actor actor, Set<String> grants) {
        if (!Permission.ALL_CODES.containsAll(grants)) {
            throw invalidField("permissions", "选择的权限已变更，请重新打开表单");
        }
        actor.requireGrants(grants);
    }

    private void setPermissions(long role, Set<String> permissions) {
        jdbc.sql("DELETE FROM fm_role_permission WHERE role_id = :role").param("role", role).update();
        for (String permission : permissions) {
            jdbc.sql("INSERT INTO fm_role_permission (role_id, permission_code) VALUES (:role, :permission)")
                    .param("role", role).param("permission", permission).update();
        }
    }

    private void setUserRoles(long user, Set<Long> roles) {
        jdbc.sql("DELETE FROM fm_user_role WHERE user_id = :user").param("user", user).update();
        for (long role : roles) {
            jdbc.sql("INSERT INTO fm_user_role (user_id, role_id) VALUES (:user, :role)")
                    .param("user", user).param("role", role).update();
        }
    }

    private Map<Long, List<RoleSummary>> rolesByUser(List<Long> ids) {
        var result = new HashMap<Long, List<RoleSummary>>();
        if (ids.isEmpty()) {
            return result;
        }
        var links = jdbc.sql("""
                SELECT ur.user_id, r.id AS role_id, r.code, r.name
                FROM fm_user_role ur JOIN fm_role r ON r.id = ur.role_id
                WHERE ur.user_id IN (:ids) ORDER BY r.id
                """).param("ids", ids).query(UserRole.class).list();
        for (UserRole link : links) {
            result.computeIfAbsent(link.userId(), ignored -> new ArrayList<>())
                    .add(new RoleSummary(link.roleId(), link.code(), link.name()));
        }
        return result;
    }

    private String encode(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidField("password", "密码长度不能超过 72 字节");
        }
        return passwords.encode(password);
    }

    record Id(long id) {}
    record UserRow(long id, String username, String displayName, boolean enabled, LocalDateTime createdAt) {}
    record UserRole(long userId, long roleId, String code, String name) {}
    record UserView(long id, String username, String displayName, boolean enabled,
                    LocalDateTime createdAt, List<RoleSummary> roles, UserProfiles.Profile profile) {}
    record RoleSummary(long id, String code, String name) {}
    record RoleView(long id, String code, String name, List<String> permissions) {}
    record PermissionChoice(String code, String label) {}
    record GrantChoice(String code, String label, boolean grantable) {}
    record PermissionGroup(String key, List<String> menuPath, List<GrantChoice> permissions) {}

    record UserCreate(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,40}", message = "用户名使用 3 至 40 位字母、数字或 . _ -")
            String username,
            @NotBlank(message = "请输入姓名") @Size(max = 80) String displayName,
            @NotBlank @Size(min = 8, max = 72, message = "密码最少 8 位") String password,
            @NotNull Boolean enabled,
            @NotEmpty(message = "请选择角色") @Size(max = 25) Set<@NotNull @Positive Long> roleIds,
            @Valid UserProfiles.Administration profile) {}

    record UserUpdate(
            @NotBlank(message = "请输入姓名") @Size(max = 80) String displayName,
            @NotNull Boolean enabled,
            @NotEmpty(message = "请选择角色") @Size(max = 25) Set<@NotNull @Positive Long> roleIds,
            @Valid UserProfiles.Administration profile) {}

    record PasswordReset(@NotBlank @Size(min = 8, max = 72, message = "密码最少 8 位") String password) {}

    record RoleCreate(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,39}", message = "角色编码使用字母、数字和下划线")
            String code,
            @NotBlank(message = "请输入角色名称") @Size(max = 80) String name,
            @NotNull @Size(max = 40) Set<@NotBlank String> permissions) {}

    record RoleUpdate(@NotBlank(message = "请输入角色名称") @Size(max = 80) String name,
                      @NotNull @Size(max = 40) Set<@NotBlank String> permissions) {}
}
