package com.tuapp.restaurante.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Permisos de inventario de la primera versión; la asignación vive aquí. */
@Service
public class InventoryPermissionService {
    public enum Permission { INVENTARIO_RECONCILIAR, INVENTARIO_ACTIVAR_5A, INVENTARIO_RECIBIR, INVENTARIO_GESTIONAR_PRESENTACIONES, INVENTARIO_REVERTIR_RECEPCION }

    private final JdbcTemplate db;

    public InventoryPermissionService(JdbcTemplate db) {
        this.db = db;
    }

    public void require(Long actorId, Permission permission) {
        Objects.requireNonNull(permission, "Permiso obligatorio");
        if (actorId == null) throw new AccessDeniedException("Usuario obligatorio");
        if (!allowed(actorId, permission)) {
            throw new AccessDeniedException("Se requiere " + permission.name());
        }
    }

    public boolean allowed(Long actorId, Permission permission) {
        Objects.requireNonNull(permission, "Permiso obligatorio");
        if (actorId == null) return false;
        Boolean allowed = db.queryForObject("select exists(select 1 from usuario " +
                "where id=? and activo=true and rol=?)", Boolean.class,
                actorId, switch (permission) {
                    case INVENTARIO_RECONCILIAR, INVENTARIO_ACTIVAR_5A, INVENTARIO_RECIBIR, INVENTARIO_GESTIONAR_PRESENTACIONES, INVENTARIO_REVERTIR_RECEPCION -> "ROLE_ADMIN";
                });
        return Boolean.TRUE.equals(allowed);
    }
}
