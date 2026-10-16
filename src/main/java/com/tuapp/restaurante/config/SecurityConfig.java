package com.tuapp.restaurante.config;

import com.tuapp.restaurante.services.UsuarioService;
import com.tuapp.restaurante.repositories.UsuarioRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final UsuarioService usuarioService;
    private final PasswordEncoder passwordEncoder;
    private final UsuarioRepository users;

    public SecurityConfig(UsuarioService usuarioService, PasswordEncoder passwordEncoder,
                          UsuarioRepository users) {
        this.usuarioService = usuarioService;
        this.passwordEncoder = passwordEncoder;
        this.users = users;
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider proveedor = new DaoAuthenticationProvider();
        proveedor.setUserDetailsService(usuarioService);
        proveedor.setPasswordEncoder(passwordEncoder);
        return proveedor;
    }

    /** Redirige al usuario según su rol después del login exitoso. */
    @Bean
    public AuthenticationSuccessHandler loginSuccessHandler() {
        return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {
            boolean isSuperAdmin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
            boolean isAdmin      = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            boolean isGerente    = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_GERENTE"));
            boolean isMesero     = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_MESERO"));
            boolean isCocinero   = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_COCINERO"));

            if (isSuperAdmin) {
                response.sendRedirect("/configuracion");
            } else if (isAdmin || isGerente) {
                response.sendRedirect("/dashboard");
            } else if (isMesero) {
                response.sendRedirect("/pos");
            } else if (isCocinero) {
                response.sendRedirect("/kds");
            } else {
                response.sendRedirect("/pos");
            }
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/img/**", "/images/**", "/uploads/**", "/webjars/**", "/menu**", "/menu/**", "/asistencia/reloj").permitAll()
                // Permisos conceptuales verificados por los casos de uso, incluida consulta para captura.
                .requestMatchers("/insumos/*/presentaciones", "/insumos/*/presentaciones/**", "/inventario/recepciones/**").authenticated()
                .requestMatchers("/superadmin/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/usuarios/**", "/configuracion/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/insumos/**", "/proveedores/**", "/categorias/**",
                        "/productos/**", "/recetas/**", "/subrecetas/**", "/movimientos/**", "/dashboard",
                        "/clientes/**", "/gastos/**", "/reportes/**", "/asistencia/reporte")
                    .hasAnyRole("ADMIN", "GERENTE", "SUPER_ADMIN")
                .requestMatchers(HttpMethod.POST, "/reservas/actualizar-horario", "/reservas/*/estado", "/reservas/*/eliminar")
                    .hasAnyRole("ADMIN", "GERENTE", "SUPER_ADMIN")
                .requestMatchers(HttpMethod.GET, "/reservas/*/editar").hasAnyRole("ADMIN", "GERENTE", "SUPER_ADMIN")
                .requestMatchers("/reservas", "/reservas/**").hasAnyRole("ADMIN", "GERENTE", "SUPER_ADMIN", "EMPLEADO", "MESERO")
                .requestMatchers("/pos/**", "/pos", "/mesas/**", "/caja/**").hasAnyRole("ADMIN", "GERENTE", "EMPLEADO", "MESERO", "SUPER_ADMIN")
                .requestMatchers("/kds/**", "/kds").hasAnyRole("ADMIN", "COCINERO", "SUPER_ADMIN")
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex.accessDeniedPage("/login?denied=true"))
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .successHandler(loginSuccessHandler())
                .failureUrl("/login?error=true")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "remember-me")
                .permitAll()
            )
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                    "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com; " +
                    "font-src 'self' data: https://cdn.jsdelivr.net https://fonts.gstatic.com; " +
                    "img-src 'self' data: https://*; " +
                    "connect-src 'self';"
                ))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(new ActiveUserFilter(users), AuthorizationFilter.class);

        return http.build();
    }
}
