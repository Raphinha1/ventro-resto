package com.tuapp.restaurante.security;

import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.repositories.UsuarioRepository;
import java.util.Arrays;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Resolve the active actor from trusted authentication and recheck the persisted role. */
@Service
public class AccessPolicy {
    private final UsuarioRepository users;

    public AccessPolicy(UsuarioRepository users) { this.users = users; }

    public Usuario require(ApplicationRole... roles) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("Autenticación requerida");
        }
        Usuario actor = users.findByUsername(auth.getName()).filter(Usuario::isActivo)
                .orElseThrow(() -> new AccessDeniedException("Usuario activo requerido"));
        ApplicationRole role;
        try { role = ApplicationRole.parse(actor.getRol()); }
        catch (IllegalArgumentException ex) { throw new AccessDeniedException("Rol no permitido"); }
        if (Arrays.stream(roles).noneMatch(role::equals)
                || auth.getAuthorities().stream().noneMatch(a -> role.authority().equals(a.getAuthority()))) {
            throw new AccessDeniedException("Permiso insuficiente");
        }
        return actor;
    }

    public Usuario requireManagement() {
        return require(ApplicationRole.ADMIN, ApplicationRole.GERENTE, ApplicationRole.SUPER_ADMIN);
    }

    public Usuario requireReservationAccess() {
        return require(ApplicationRole.ADMIN, ApplicationRole.GERENTE, ApplicationRole.SUPER_ADMIN,
                ApplicationRole.EMPLEADO, ApplicationRole.MESERO);
    }

    public Usuario requireUserManagement() {
        return require(ApplicationRole.ADMIN, ApplicationRole.SUPER_ADMIN);
    }

    public void requireUserChange(Usuario target, String requestedRole) {
        Usuario actor = requireUserManagement();
        boolean protectedChange = (target != null && ApplicationRole.parse(target.getRol()) == ApplicationRole.SUPER_ADMIN)
                || (requestedRole != null && ApplicationRole.parse(requestedRole) == ApplicationRole.SUPER_ADMIN);
        if (protectedChange && ApplicationRole.parse(actor.getRol()) != ApplicationRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Solo SUPER_ADMIN puede gestionar este rol");
        }
    }
}
