package com.firemanage;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.firemanage.ApiSupport.unauthorized;
import static org.apache.commons.lang3.StringUtils.strip;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthenticationManager manager;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository tokens;
    private final AccessService access;
    private final MenuController menus;
    private final JdbcClient jdbc;
    private final UserProfiles profiles;

    AuthController(AuthenticationManager manager, HttpSessionSecurityContextRepository contexts,
                   HttpSessionCsrfTokenRepository tokens, AccessService access, MenuController menus,
                   JdbcClient jdbc, UserProfiles profiles) {
        this.manager = manager;
        this.contexts = contexts;
        this.tokens = tokens;
        this.access = access;
        this.menus = menus;
        this.jdbc = jdbc;
        this.profiles = profiles;
    }

    @GetMapping("/csrf")
    CsrfReply csrf(CsrfToken token) {
        return new CsrfReply(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    Me login(@Valid @RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
        if (credentials.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw unauthorized("账号或密码有误，或账号已停用");
        }
        Authentication authentication;
        try {
            authentication = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                    AccessService.normalizeUsername(credentials.username()), credentials.password()));
        } catch (AuthenticationException failure) {
            throw unauthorized("账号或密码有误，或账号已停用");
        }
        AccessService.Account account = (AccessService.Account) authentication.getPrincipal();
        AccessService.Actor actor = access.resolveActor(account);
        request.getSession();
        request.changeSessionId();
        tokens.saveToken(null, request, response);

        AccessService.SessionAccount principal = new AccessService.SessionAccount(account.id(), account.username(), account.authVersion());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, Collections.emptyList()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return reply(actor);
    }

    @GetMapping("/me")
    Me me(Authentication authentication) {
        return reply(access.actor(authentication));
    }

    @PutMapping("/profile")
    @Transactional
    Me updateProfile(Authentication authentication, @Valid @RequestBody ProfileUpdate request) {
        AccessService.Actor actor = access.actor(authentication);
        AccessService.SessionAccount principal = (AccessService.SessionAccount) authentication.getPrincipal();
        jdbc.sql("SELECT id FROM fm_user WHERE id = :id AND enabled = 1 AND auth_version = :version FOR UPDATE\n")
                .param("id", actor.id()).param("version", principal.authVersion()).query(Long.class).optional()
                .orElseThrow(() -> unauthorized("登录已失效，请重新登录"));
        jdbc.sql("UPDATE fm_user SET display_name = :name WHERE id = :id")
                .param("name", strip(request.displayName())).param("id", actor.id()).update();
        profiles.save(actor.id(), request.profile(), "profile.");
        return me(authentication);
    }

    private Me reply(AccessService.Actor actor) {
        return new Me(actor.id(), actor.username(), actor.displayName(), actor.roles(),
                actor.sortedPermissions(), menus.navigation(actor),
                profiles.find(Collections.singletonList(actor.id())).getOrDefault(actor.id(), UserProfiles.EMPTY));
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Credentials {
        @NotBlank(message = "请输入用户名") @Size(max = 40) String username;
        @NotBlank(message = "请输入密码") @Size(max = 72) String password;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class CsrfReply {
        String header;
        String token;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class ProfileUpdate {
        @NotBlank(message = "请输入姓名") @Size(max = 80) String displayName;
        @NotNull @Valid UserProfiles.Edit profile;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Me {
        long id;
        String username;
        String displayName;
        List<String> roles;
        List<String> permissions;
        List<MenuController.Navigation> menus;
        UserProfiles.Profile profile;
    }
}
