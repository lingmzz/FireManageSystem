package com.firemanage;

import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import static com.firemanage.ApiSupport.forbidden;
import static com.firemanage.ApiSupport.unauthorized;

@Service
class AccessService implements UserDetailsService {
    static final String ADMIN_ROLE = "ADMIN";
    static final String BOOTSTRAP_USER = "admin";

    private final JdbcClient jdbc;

    AccessService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.sql("""
                SELECT id, username, display_name, password_hash, enabled, auth_version
                FROM fm_user WHERE username = :username
                """).param("username", normalizeUsername(username)).query(Account.class)
                .optional().orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    }

    Actor actor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAccount principal)) {
            throw unauthorized("请先登录");
        }
        var account = jdbc.sql("""
                SELECT id, username, display_name, password_hash, enabled, auth_version
                FROM fm_user WHERE id = :id
                """).param("id", principal.id()).query(Account.class).optional();
        if (account.isEmpty() || !account.get().enabled()
                || account.get().authVersion() != principal.authVersion()) {
            throw unauthorized("登录已失效，请重新登录");
        }
        return resolveActor(account.get());
    }

    Actor resolveActor(Account account) {
        var roles = jdbc.sql("""
                SELECT r.code FROM fm_role r JOIN fm_user_role ur ON ur.role_id = r.id
                WHERE ur.user_id = :userId ORDER BY r.code
                """).param("userId", account.id()).query(String.class).list();
        Set<String> permissions = roles.contains(ADMIN_ROLE) ? Permission.ALL_CODES : permissionsForUser(account.id());
        return new Actor(account.id(), account.username(), account.displayName(), roles, permissions);
    }

    Set<String> permissionsForUser(long userId) {
        return Set.copyOf(jdbc.sql("""
                SELECT DISTINCT rp.permission_code
                FROM fm_user_role ur JOIN fm_role_permission rp ON rp.role_id = ur.role_id
                WHERE ur.user_id = :userId
                """).param("userId", userId).query(String.class).list());
    }

    Set<String> permissionsForRole(long roleId) {
        return Set.copyOf(jdbc.sql("""
                SELECT permission_code FROM fm_role_permission WHERE role_id = :roleId
                """).param("roleId", roleId).query(String.class).list());
    }

    static String normalizeUsername(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    record Account(long id, String username, String displayName, String passwordHash,
                   boolean enabled, int authVersion) implements UserDetails {
        @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
        @Override public String getPassword() { return passwordHash; }
        @Override public String getUsername() { return username; }
        @Override public boolean isEnabled() { return enabled; }
    }

    record SessionAccount(long id, String username, int authVersion) implements Principal {
        @Override public String getName() { return username; }
    }

    record Actor(long id, String username, String displayName, List<String> roles, Set<String> permissions) {
        boolean admin() {
            return roles.contains(ADMIN_ROLE);
        }

        boolean has(Permission permission) {
            return permissions.contains(permission.code);
        }

        void require(Permission permission) {
            if (!has(permission)) {
                throw forbidden();
            }
        }

        void requireGrants(Set<String> grants) {
            if (!permissions.containsAll(grants)) {
                throw forbidden();
            }
        }

        List<String> sortedPermissions() {
            return permissions.stream().sorted().collect(Collectors.toList());
        }
    }
}
