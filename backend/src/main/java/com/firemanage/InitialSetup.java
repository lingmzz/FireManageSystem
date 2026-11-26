package com.firemanage;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class InitialSetup implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;

    InitialSetup(JdbcClient jdbc, PasswordEncoder passwords, Environment environment) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Serialize first-time setup if multiple application instances start together.
        jdbc.sql("SELECT flow_type FROM fm_workflow_config WHERE flow_type = 'WORKBENCH' FOR UPDATE")
                .query(String.class).single();
        ensureBasicSetup();
        ensureFireSetup();
        if (markFeature("workbench-label-v1")) {
            jdbc.sql("UPDATE fm_menu SET name = '工作台' WHERE view_key = 'approvals' AND name = '审批工作台'").update();
        }
        if (markFeature("approval-flow-menu-v1")) {
            Long parent = jdbc.limit("SELECT COALESCE(parent_id, 0) FROM fm_menu WHERE view_key = 'roles' ORDER BY id", 1)
                    .query(Long.class).optional().orElse(0L);
            long existing = jdbc.sql("SELECT COUNT(*) FROM fm_menu WHERE view_key = :view")
                    .param("view", MenuController.View.WORKFLOWS.key).query(Long.class).single();
            if (existing == 0) {
                menu(parent == 0 ? null : parent, MenuController.View.WORKFLOWS.label, MenuController.View.WORKFLOWS.key, 40);
            }
        }
        if (markFeature("fire-dashboard-v1")) {
            long existing = jdbc.sql("SELECT COUNT(*) FROM fm_menu WHERE view_key = :view")
                    .param("view", MenuController.View.FIRE_DASHBOARD.key).query(Long.class).single();
            if (existing == 0) {
                menu(null, MenuController.View.FIRE_DASHBOARD.label, MenuController.View.FIRE_DASHBOARD.key, 1);
            }
            List<Long> fireRoles = jdbc.sql("SELECT DISTINCT role_id FROM fm_role_permission\n"
                    + "WHERE permission_code IN ('fire:device-read', 'fire:hazard-read', 'fire:drill-read', 'fire:maint-read', 'fire:org')")
                    .query(Long.class).list();
            for (Long roleId : fireRoles) {
                long granted = jdbc.sql("SELECT COUNT(*) FROM fm_role_permission WHERE role_id = :role AND permission_code = :code")
                        .param("role", roleId).param("code", Permission.FIRE_DASHBOARD.code).query(Long.class).single();
                if (granted == 0) {
                    jdbc.sql("INSERT INTO fm_role_permission (role_id, permission_code) VALUES (:role, :code)")
                            .param("role", roleId).param("code", Permission.FIRE_DASHBOARD.code).update();
                }
            }
        }
    }

    private void ensureBasicSetup() {
        if (jdbc.sql("SELECT COUNT(*) FROM fm_user").query(Long.class).single() > 0) {
            return;
        }
        String password = environment.getProperty("fire.bootstrap-password", "");
        if (password.length() < 10) {
            throw new IllegalStateException("首次启动需要 fire.bootstrap-password，长度至少 10 位");
        }

        long adminRole = role(AccessService.ADMIN_ROLE, "系统管理员");
        role("REPORTER", "巡检员", Permission.APPROVAL_READ, Permission.APPROVAL_CREATE);
        role("DISPATCHER", "整改指派员", Permission.APPROVAL_READ, Permission.APPROVAL_ASSIGN);
        role("RECTIFIER", "整改办理员", Permission.APPROVAL_READ, Permission.APPROVAL_RECTIFY);
        role("REVIEWER", "复核员", Permission.APPROVAL_READ, Permission.APPROVAL_REVIEW);

        long user = jdbc.sql("INSERT INTO fm_user (id, username, display_name, password_hash)\n"
                + "VALUES (:newId, :username, '系统管理员', :password)\n")
                .param("username", AccessService.BOOTSTRAP_USER).param("password", passwords.encode(password)).insert();
        jdbc.sql("INSERT INTO fm_user_role (user_id, role_id) VALUES (:userId, :roleId)")
                .param("userId", user).param("roleId", adminRole).update();

        menu(null, MenuController.View.APPROVALS.label, MenuController.View.APPROVALS.key, 10);
        long settings = menu(null, "系统管理", null, 20);
        menu(settings, MenuController.View.USERS.label, MenuController.View.USERS.key, 10);
        menu(settings, MenuController.View.ROLES.label, MenuController.View.ROLES.key, 20);
        menu(settings, MenuController.View.MENUS.label, MenuController.View.MENUS.key, 30);
    }

    private void ensureFireSetup() {
        if (!markFeature("station-fire-v1")) {
            return;
        }
        jdbc.sql("INSERT INTO fm_fire_unit (id, code, name, kind)\n"
                + "SELECT fm_id_seq.NEXTVAL, 'CHANGCHE', '长车段', 'SEGMENT' FROM dual\n"
                + "WHERE NOT EXISTS (SELECT 1 FROM fm_fire_unit WHERE code = 'CHANGCHE')").update();

        addFireRole("FIRE_STATION", "车站消防管理员",
                Permission.FIRE_DASHBOARD,
                Permission.FIRE_DEVICE_READ, Permission.FIRE_DEVICE_ADD, Permission.FIRE_DEVICE_OPERATE,
                Permission.FIRE_MAINT_READ, Permission.FIRE_DRILL_READ, Permission.FIRE_DRILL_WRITE,
                Permission.FIRE_HAZARD_READ, Permission.FIRE_HAZARD_ADD, Permission.FIRE_HAZARD_REPORT);
        addFireRole("FIRE_DEPARTMENT", "科室消防管理员",
                Permission.FIRE_DASHBOARD,
                Permission.FIRE_DEVICE_READ, Permission.FIRE_DEVICE_ADD, Permission.FIRE_DEVICE_EDIT,
                Permission.FIRE_DEVICE_OPERATE, Permission.FIRE_DEVICE_IMPORT, Permission.FIRE_MAINT_READ,
                Permission.FIRE_DRILL_READ, Permission.FIRE_HAZARD_READ, Permission.FIRE_HAZARD_ADD,
                Permission.FIRE_HAZARD_REPORT, Permission.FIRE_HAZARD_CONFIRM);
        addFireRole("FIRE_SEGMENT", "车务段消防管理员",
                Permission.FIRE_DASHBOARD,
                Permission.FIRE_DEVICE_READ, Permission.FIRE_DEVICE_ADD, Permission.FIRE_DEVICE_EDIT,
                Permission.FIRE_DEVICE_OPERATE, Permission.FIRE_DEVICE_IMPORT, Permission.FIRE_MAINT_READ,
                Permission.FIRE_MAINT_WRITE, Permission.FIRE_DRILL_READ, Permission.FIRE_DRILL_WRITE,
                Permission.FIRE_HAZARD_READ, Permission.FIRE_HAZARD_ADD, Permission.FIRE_HAZARD_REPORT,
                Permission.FIRE_HAZARD_CLOSE);

        long business = menu(null, "消防业务", null, 5);
        menu(business, MenuController.View.FIRE_DEVICES.label, MenuController.View.FIRE_DEVICES.key, 10);
        menu(business, MenuController.View.FIRE_MAINTAINERS.label, MenuController.View.FIRE_MAINTAINERS.key, 20);
        menu(business, MenuController.View.FIRE_DRILLS.label, MenuController.View.FIRE_DRILLS.key, 30);
        menu(business, MenuController.View.FIRE_HAZARDS.label, MenuController.View.FIRE_HAZARDS.key, 40);
        menu(business, MenuController.View.FIRE_UNITS.label, MenuController.View.FIRE_UNITS.key, 50);
    }

    private boolean markFeature(String code) {
        return jdbc.sql("INSERT INTO fm_feature (code) SELECT :code FROM dual\n"
                + "WHERE NOT EXISTS (SELECT 1 FROM fm_feature WHERE code = :code)").param("code", code).update() > 0;
    }

    private void addFireRole(String code, String name, Permission... permissions) {
        if (jdbc.sql("SELECT COUNT(*) FROM fm_role WHERE code = :code").param("code", code).query(Long.class).single() == 0) {
            role(code, name, permissions);
        }
    }

    private long role(String code, String name, Permission... permissions) {
        long id = jdbc.sql("INSERT INTO fm_role (id, code, name) VALUES (:newId, :code, :name)")
                .param("code", code).param("name", name).insert();
        for (Permission permission : permissions) {
            jdbc.sql("INSERT INTO fm_role_permission (role_id, permission_code) VALUES (:role, :permission)")
                    .param("role", id).param("permission", permission.code).update();
        }
        return id;
    }

    private long menu(Long parent, String name, String view, int order) {
        return jdbc.sql("INSERT INTO fm_menu (id, parent_id, name, view_key, sort_order) VALUES (:newId, :parent, :name, :view, :order)\n")
                .param("parent", parent).param("name", name).param("view", view).param("order", order).insert();
    }
}
