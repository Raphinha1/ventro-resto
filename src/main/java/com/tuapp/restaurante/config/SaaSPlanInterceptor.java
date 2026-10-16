package com.tuapp.restaurante.config;

import com.tuapp.restaurante.models.ConfiguracionSistema;
import com.tuapp.restaurante.services.ConfiguracionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class SaaSPlanInterceptor implements HandlerInterceptor {

    private final ConfiguracionService configuracionService;

    public SaaSPlanInterceptor(ConfiguracionService configuracionService) {
        this.configuracionService = configuracionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Super Admin has full access to everything, always
        if (request.isUserInRole("ROLE_SUPER_ADMIN")) return true;

        String uri = request.getRequestURI();
        ConfiguracionSistema cfg = configuracionService.obtener();
        if (cfg == null) return true;

        String plan = cfg.getNivelPlan() != null ? cfg.getNivelPlan() : "PREMIUM";

        // PREMIUM has full access — no restrictions
        if ("PREMIUM".equals(plan)) return true;

        // Evaluate access per module
        boolean allow = switch (plan) {
            case "LITE" -> isLiteAllowed(uri);
            case "PRO"  -> isProAllowed(uri);
            case "CUSTOM" -> isCustomAllowed(uri, cfg);
            default -> true;
        };

        if (!allow) {
            response.sendRedirect("/dashboard?error=plan_restricted");
            return false;
        }
        return true;
    }

    private boolean isLiteAllowed(String uri) {
        // LITE: only POS and inventory core
        return !startsWith(uri, "/mesas", "/reservas", "/kds", "/clientes",
                           "/caja", "/pos/historial", "/reportes", "/gastos",
                           "/asistencia", "/ia");
    }

    private boolean isProAllowed(String uri) {
        // PRO: adds mesas, reservas, kds, clientes — blocks finanzas, ia, asistencia
        return !startsWith(uri, "/caja", "/pos/historial", "/reportes", "/gastos",
                           "/asistencia", "/ia");
    }

    private boolean isCustomAllowed(String uri, ConfiguracionSistema cfg) {
        if (uri.startsWith("/mesas")        && !Boolean.TRUE.equals(cfg.getModMesas()))       return false;
        if (uri.startsWith("/reservas")     && !Boolean.TRUE.equals(cfg.getModReservas()))    return false;
        if (uri.startsWith("/kds")          && !Boolean.TRUE.equals(cfg.getModKds()))         return false;
        if (uri.startsWith("/clientes")     && !Boolean.TRUE.equals(cfg.getModClientes()))    return false;
        if (uri.startsWith("/caja")         && !Boolean.TRUE.equals(cfg.getModCaja()))        return false;
        if (uri.startsWith("/pos/historial")&& !Boolean.TRUE.equals(cfg.getModHistorial()))   return false;
        if (uri.startsWith("/reportes")     && !Boolean.TRUE.equals(cfg.getModReportes()))    return false;
        if (uri.startsWith("/gastos")       && !Boolean.TRUE.equals(cfg.getModGastos()))      return false;
        if (uri.startsWith("/asistencia")   && !Boolean.TRUE.equals(cfg.getModAsistencia()))  return false;
        if (uri.startsWith("/ia")           && !Boolean.TRUE.equals(cfg.getModIa()))          return false;
        return true;
    }

    private boolean startsWith(String uri, String... prefixes) {
        for (String p : prefixes) if (uri.startsWith(p)) return true;
        return false;
    }
}

