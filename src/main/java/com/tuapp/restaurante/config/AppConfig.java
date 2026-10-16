package com.tuapp.restaurante.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración general de la aplicación.
 * Separamos el PasswordEncoder aquí para evitar la dependencia circular:
 *   SecurityConfig → UsuarioService → PasswordEncoder → SecurityConfig
 */
@Configuration
public class AppConfig implements WebMvcConfigurer {

    private final SaaSPlanInterceptor saasPlanInterceptor;
    private final DeploymentStorage storage;

    public AppConfig(SaaSPlanInterceptor saasPlanInterceptor, DeploymentStorage storage) {
        this.saasPlanInterceptor = saasPlanInterceptor;
        this.storage = storage;
    }

    /**
     * Define el algoritmo de hashing de contraseñas.
     * BCrypt es el estándar de la industria para almacenar contraseñas de forma segura.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(saasPlanInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/css/**", "/js/**", "/img/**", "/uploads/**", "/login", "/logout", "/error", "/dashboard", "/configuracion/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(storage.root().toUri().toString());
    }
}
