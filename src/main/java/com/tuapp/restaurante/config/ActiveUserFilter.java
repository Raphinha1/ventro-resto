package com.tuapp.restaurante.config;

import com.tuapp.restaurante.repositories.UsuarioRepository;
import com.tuapp.restaurante.security.ApplicationRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Revoca sesiones existentes tras desactivar o cambiar el rol de un usuario. */
final class ActiveUserFilter extends OncePerRequestFilter {
    private final UsuarioRepository users;

    ActiveUserFilter(UsuarioRepository users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            boolean valid = users.findByUsername(auth.getName()).filter(user -> user.isActivo() &&
                    auth.getAuthorities().stream().anyMatch(authority ->
                            authority.getAuthority().equals(normalizeRole(user.getRol())))).isPresent();
            if (!valid) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                Cookie cookie = new Cookie("JSESSIONID", "");
                cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
                cookie.setMaxAge(0);
                cookie.setHttpOnly(true);
                response.addCookie(cookie);
                response.sendRedirect("/login?expired=true");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private String normalizeRole(String role) {
        try { return ApplicationRole.parse(role).authority(); }
        catch (IllegalArgumentException ex) { return ""; }
    }
}
