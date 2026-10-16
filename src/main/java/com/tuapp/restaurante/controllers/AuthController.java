package com.tuapp.restaurante.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controlador de autenticación.
 * Maneja las vistas de Login y Dashboard principal.
 *
 * @Controller → Marca esta clase como controlador MVC (retorna nombres de
 *             vistas).
 */
@Controller
public class AuthController {

    /**
     * Muestra la página de login.
     * Spring Security intercepta el POST a /login automáticamente.
     *
     * @GetMapping → Maneja peticiones HTTP GET.
     */
    @GetMapping("/login")
    public String mostrarLogin() {
        // Retorna el nombre de la plantilla: templates/login.html
        return "login";
    }

    // /dashboard is mapped in DashboardController

    /**
     * Redirige la raíz al dashboard.
     */
    @GetMapping("/")
    public String inicio() {
        return "redirect:/dashboard";
    }
}
