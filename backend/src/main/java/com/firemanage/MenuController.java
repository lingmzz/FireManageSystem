package com.firemanage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.firemanage.ApiSupport.invalidField;
import static com.firemanage.ApiSupport.missing;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.strip;

@RestController
@RequestMapping("/api/menus")
class MenuController {
    private static final String MENU_QUERY = "SELECT id, parent_id, name, view_key, permission_code, sort_order, enabled\n"
            + "FROM fm_menu ORDER BY sort_order, id\n";

    private final JdbcClient jdbc;
    private final AccessService access;

    MenuController(JdbcClient jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping
    List<Menu> list(Authentication authentication) {
        access.actor(authentication).require(Permission.MENUS);
        return loadMenus(false);
    }

    @GetMapping("/options")
    MenuOptions options(Authentication authentication) {
        access.actor(authentication).require(Permission.MENUS);
        return new MenuOptions(Arrays.stream(View.values())
                .map(view -> new Option(view.key, view.label)).collect(Collectors.toList()),
                Arrays.stream(Permission.values()).map(permission -> new Option(permission.code, permission.label)).collect(Collectors.toList()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Id create(Authentication authentication, @Valid @RequestBody MenuRequest request) {
        access.actor(authentication).require(Permission.MENUS);
        validate(request, null, loadMenus(true));
        long menuId = values(jdbc.sql("INSERT INTO fm_menu (id, parent_id, name, view_key, permission_code, sort_order, enabled)\n"
                + "VALUES (:newId, :parent, :name, :view, :permission, :sort, :enabled)\n"), request).insert();
        return new Id(menuId);
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void update(Authentication authentication, @PathVariable long id, @Valid @RequestBody MenuRequest request) {
        access.actor(authentication).require(Permission.MENUS);
        List<Menu> items = loadMenus(true);
        find(items, id);
        validate(request, id, items);
        values(jdbc.sql("UPDATE fm_menu SET parent_id = :parent, name = :name, view_key = :view,\n"
                + "    permission_code = :permission, sort_order = :sort, enabled = :enabled\n"
                + "WHERE id = :id\n"), request).param("id", id).update();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(Authentication authentication, @PathVariable long id) {
        access.actor(authentication).require(Permission.MENUS);
        List<Menu> items = loadMenus(true);
        find(items, id);
        if (items.stream().anyMatch(menu -> Objects.equals(menu.parentId(), id))) {
            throw ApiSupport.conflict("请先移走该菜单下的子菜单");
        }
        jdbc.sql("DELETE FROM fm_menu WHERE id = :id").param("id", id).update();
    }

    List<Navigation> navigation(AccessService.Actor actor) {
        Map<Long, List<Menu>> groups = new HashMap<>();
        for (Menu menu : loadMenus(false)) {
            long parent = menu.parentId() == null ? 0L : menu.parentId();
            groups.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(menu);
        }
        return visibleChildren(0L, groups, actor);
    }

    Map<String, List<String>> viewPaths() {
        List<Menu> items = loadMenus(false);
        HashMap<Long, Menu> byId = new HashMap<>();
        for (Menu item : items) byId.put(item.id(), item);
        HashMap<String, List<String>> paths = new HashMap<>();
        for (Menu item : items) {
            if (item.viewKey() == null || paths.containsKey(item.viewKey())) continue;
            ArrayList<String> path = new ArrayList<>();
            HashSet<Long> seen = new HashSet<>();
            for (Menu node = item; node != null; node = byId.get(node.parentId())) {
                if (!node.enabled() || !seen.add(node.id())) {
                    path.clear();
                    break;
                }
                path.add(0, node.name());
            }
            if (!path.isEmpty()) paths.put(item.viewKey(), path);
        }
        return paths;
    }

    private List<Navigation> visibleChildren(long parent, Map<Long, List<Menu>> groups, AccessService.Actor actor) {
        ArrayList<Navigation> result = new ArrayList<>();
        for (Menu menu : groups.getOrDefault(parent, Collections.emptyList())) {
            if (!menu.enabled() || (menu.permissionCode() != null && !actor.permissions().contains(menu.permissionCode()))) {
                continue;
            }
            View view = View.find(menu.viewKey());
            List<Navigation> children = visibleChildren(menu.id(), groups, actor);
            if ((view == null || !actor.has(view.required)) && children.isEmpty()) {
                continue;
            }
            String viewKey = view != null && actor.has(view.required) ? view.key : null;
            result.add(new Navigation(menu.id(), menu.name(), viewKey, children));
        }
        return result;
    }

    private List<Menu> loadMenus(boolean lock) {
        return jdbc.sql(lock ? MENU_QUERY + " FOR UPDATE" : MENU_QUERY).query(Menu.class).list();
    }

    private static Menu find(List<Menu> menus, long id) {
        return menus.stream().filter(menu -> menu.id() == id).findFirst()
                .orElseThrow(() -> missing("菜单不存在"));
    }

    private static void validate(MenuRequest request, Long target, List<Menu> menus) {
        String view = clean(request.viewKey());
        String permission = clean(request.permissionCode());
        if (view != null && View.find(view) == null) {
            throw invalidField("viewKey", "请选择已有的管理页面");
        }
        if (permission != null && !Permission.ALL_CODES.contains(permission)) {
            throw invalidField("permissionCode", "请选择有效的菜单权限");
        }
        Long parent = request.parentId();
        HashSet<Long> seen = new HashSet<>();
        while (parent != null) {
            if (Objects.equals(parent, target) || !seen.add(parent)) {
                throw invalidField("parentId", "父菜单不能形成循环");
            }
            Long parentId = parent;
            parent = menus.stream().filter(menu -> Objects.equals(menu.id(), parentId)).findFirst()
                    .orElseThrow(() -> invalidField("parentId", "请选择有效的父菜单")).parentId();
        }
    }

    private static JdbcClient.StatementSpec values(JdbcClient.StatementSpec statement, MenuRequest request) {
        return statement.param("parent", request.parentId()).param("name", strip(request.name()))
                .param("view", clean(request.viewKey())).param("permission", clean(request.permissionCode()))
                .param("sort", request.sortOrder()).param("enabled", request.enabled());
    }

    private static String clean(String value) {
        return value == null || isBlank(value) ? null : strip(value);
    }

    enum View {
        FIRE_DASHBOARD("fire-dashboard", "首页概览", Permission.FIRE_DASHBOARD),
        FIRE_DEVICES("fire-devices", "消防设备台账", Permission.FIRE_DEVICE_READ,
                Permission.FIRE_DEVICE_ADD, Permission.FIRE_DEVICE_EDIT, Permission.FIRE_DEVICE_OPERATE,
                Permission.FIRE_DEVICE_IMPORT),
        FIRE_MAINTAINERS("fire-maintainers", "维保单位", Permission.FIRE_MAINT_READ, Permission.FIRE_MAINT_WRITE),
        FIRE_DRILLS("fire-drills", "应急演练台账", Permission.FIRE_DRILL_READ, Permission.FIRE_DRILL_WRITE),
        FIRE_HAZARDS("fire-hazards", "隐患问题库", Permission.FIRE_HAZARD_READ,
                Permission.FIRE_HAZARD_ADD, Permission.FIRE_HAZARD_REPORT, Permission.FIRE_HAZARD_CONFIRM,
                Permission.FIRE_HAZARD_CLOSE),
        FIRE_UNITS("fire-units", "消防单位与授权", Permission.FIRE_ORG),
        APPROVALS("approvals", "工作台", Permission.APPROVAL_READ,
                Permission.APPROVAL_CREATE, Permission.APPROVAL_ASSIGN, Permission.APPROVAL_RECTIFY,
                Permission.APPROVAL_REVIEW, Permission.APPROVAL_ALL),
        USERS("users", "用户管理", Permission.USERS),
        ROLES("roles", "角色权限", Permission.ROLES),
        MENUS("menus", "菜单管理", Permission.MENUS),
        WORKFLOWS("approval-flows", "审批流程配置", Permission.WORKFLOWS);

        final String key;
        final String label;
        final Permission required;
        final List<Permission> permissions;

        View(String key, String label, Permission required, Permission... operations) {
            this.key = key;
            this.label = label;
            this.required = required;
            ArrayList<Permission> options = new ArrayList<>();
            options.add(required);
            Collections.addAll(options, operations);
            this.permissions = Collections.unmodifiableList(options);
        }

        static View find(String key) {
            return Arrays.stream(values()).filter(view -> view.key.equals(key)).findFirst().orElse(null);
        }
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Id {
        long id;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Menu {
        long id;
        Long parentId;
        String name;
        String viewKey;
        String permissionCode;
        int sortOrder;
        boolean enabled;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Navigation {
        long id;
        String name;
        String viewKey;
        List<Navigation> children;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Option {
        String value;
        String label;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class MenuOptions {
        List<Option> views;
        List<Option> permissions;
    }
    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class MenuRequest {
        @Positive Long parentId;
        @NotBlank(message = "请输入菜单名称") @Size(max = 60) String name;
        @Size(max = 32) String viewKey;
        @Size(max = 48) String permissionCode;
        @NotNull Integer sortOrder;
        @NotNull Boolean enabled;
    }
}
