package com.tuapp.restaurante;

import com.tuapp.restaurante.security.ApplicationRole;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Explicit authentication for direct use-case fixtures, including worker threads. */
public final class SyntheticSecurity {
    private SyntheticSecurity() {}

    public static void runAs(JdbcTemplate db, Long actorId, Runnable operation) {
        var user = db.queryForMap("select username,rol from usuario where id=?", actorId);
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.get("username"),
                "synthetic", List.of(new SimpleGrantedAuthority(ApplicationRole.parse((String)user.get("rol")).authority()))));
        try {
            SecurityContextHolder.setContext(context);
            operation.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
