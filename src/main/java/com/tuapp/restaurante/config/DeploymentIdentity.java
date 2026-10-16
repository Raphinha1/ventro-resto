package com.tuapp.restaurante.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.repositories.UsuarioRepository;

import java.util.UUID;

/** Identidad inmutable del despliegue; nunca se deriva de una petición HTTP. */
@Configuration
public class DeploymentIdentity {
    private final UUID restaurantId;
    private final UUID branchId;

    public DeploymentIdentity(@Value("${ventro.restaurant-id}") UUID restaurantId,
                              @Value("${ventro.branch-id}") UUID branchId) {
        this.restaurantId = restaurantId;
        this.branchId = branchId;
    }

    public UUID restaurantId() { return restaurantId; }
    public UUID branchId() { return branchId; }

    @Bean
    @DependsOn("flywayInitializer")
    SmartInitializingSingleton validateDeploymentBeforeServing(JdbcTemplate db, UsuarioRepository users,
                                                                 PasswordEncoder encoder, Environment environment) {
        return () -> {
            db.update("insert into deployment_identity(singleton,restaurant_id,branch_id) " +
                    "values (1,?,?) on conflict (singleton) do nothing", restaurantId, branchId);
            Integer rows = db.queryForObject("select count(*) from deployment_identity", Integer.class);
            if (rows == null || rows != 1) {
                throw new IllegalStateException("La BD no pertenece a un unico restaurante");
            }
            boolean matches = Boolean.TRUE.equals(db.queryForObject(
                    "select restaurant_id=? and branch_id=? from deployment_identity where singleton=1",
                    Boolean.class, restaurantId, branchId));
            if (!matches) {
                throw new IllegalStateException("La identidad configurada no coincide con la BD del despliegue");
            }
            if (users.count() != 0) return;
            String username = environment.getProperty("ventro.bootstrap-admin-username");
            String password = environment.getProperty("ventro.bootstrap-admin-password");
            if (username == null || !username.matches("[A-Za-z0-9._-]{3,50}") ||
                    password == null || password.length() < 16 || password.isBlank()) {
                throw new IllegalStateException("BD sin usuarios: configurar VENTRO_BOOTSTRAP_ADMIN_USERNAME " +
                        "y VENTRO_BOOTSTRAP_ADMIN_PASSWORD (al menos 16 caracteres)");
            }
            Usuario admin = new Usuario();
            admin.setUsername(username);
            admin.setPassword(encoder.encode(password));
            admin.setNombreCompleto("Administrador del Restaurante");
            admin.setRol("ROLE_ADMIN");
            admin.setActivo(true);
            users.save(admin);
        };
    }
}
