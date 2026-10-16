package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.ConfiguracionSistema;
import com.tuapp.restaurante.dto.ConfiguracionUpdate;
import com.tuapp.restaurante.security.AccessPolicy;
import com.tuapp.restaurante.security.ApplicationRole;
import org.springframework.security.access.AccessDeniedException;
import com.tuapp.restaurante.repositories.ConfiguracionRepository;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfiguracionService {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;

    private final ConfiguracionRepository configuracionRepository;

    private final AccessPolicy access;
    private final JdbcTemplate db;

    public ConfiguracionService(ConfiguracionRepository configuracionRepository, AccessPolicy access, JdbcTemplate db) {
        this.db = db;
        this.access = access;
        this.configuracionRepository = configuracionRepository;
    }

    /** Obtiene la configuración del sistema (singleton, id=1). Si no existe, devuelve valores por defecto. */
    @Transactional(readOnly = true)
    public ConfiguracionSistema obtener() {
        return configuracionRepository.findById(1L)
                .orElseGet(ConfiguracionSistema::new);
    }

    /** Merge only explicitly editable fields into the persisted singleton. */
    @Transactional
    public ConfiguracionSistema guardar(ConfiguracionUpdate input) {
        var actor = access.requireUserManagement();
        boolean master = ApplicationRole.parse(actor.getRol()) == ApplicationRole.SUPER_ADMIN;
        if (!master && input.hasMasterFields()) {
            throw new AccessDeniedException("Los campos maestros requieren SUPER_ADMIN");
        }
        // The singleton may not exist yet. This transaction lock serializes both
        // first creation and updates, so an ADMIN cannot restore stale master fields.
        // Its two-int namespace is distinct from receipt/reversal and inventory locks.
        db.queryForObject("select pg_advisory_xact_lock(?,?) is null", Boolean.class, 0x43464753, 1);
        ConfiguracionSistema config = obtener();
        // OSIV may have loaded this singleton in ControllerAdvice before the lock.
        // Refresh only this entity; clearing the request context would discard unrelated state.
        if (entities.contains(config)) entities.refresh(config);
        config.setId(1L);
        if (master) {
            if (input.getNivelPlan() != null) config.setNivelPlan(input.getNivelPlan());
            if (input.getModMesas() != null) config.setModMesas(input.getModMesas());
            if (input.getModReservas() != null) config.setModReservas(input.getModReservas());
            if (input.getModKds() != null) config.setModKds(input.getModKds());
            if (input.getModClientes() != null) config.setModClientes(input.getModClientes());
            if (input.getModCaja() != null) config.setModCaja(input.getModCaja());
            if (input.getModHistorial() != null) config.setModHistorial(input.getModHistorial());
            if (input.getModReportes() != null) config.setModReportes(input.getModReportes());
            if (input.getModGastos() != null) config.setModGastos(input.getModGastos());
            if (input.getModAsistencia() != null) config.setModAsistencia(input.getModAsistencia());
            if (input.getModIa() != null) config.setModIa(input.getModIa());
            if (input.getFechaExpiracion() != null) config.setFechaExpiracion(input.getFechaExpiracion());
            if (input.getLimiteUsuarios() != null) config.setLimiteUsuarios(input.getLimiteUsuarios());
            if (input.getLimiteProductos() != null) config.setLimiteProductos(input.getLimiteProductos());
            if (input.getLimiteDispositivos() != null) config.setLimiteDispositivos(input.getLimiteDispositivos());
            if (input.getPrecioPersonalizado() != null) config.setPrecioPersonalizado(input.getPrecioPersonalizado());
            if (input.getNotasMaestras() != null) config.setNotasMaestras(input.getNotasMaestras());
        } else {
            if (input.getNombreNegocio() != null) config.setNombreNegocio(input.getNombreNegocio());
            if (input.getSlogan() != null) config.setSlogan(input.getSlogan());
            if (input.getRfc() != null) config.setRfc(input.getRfc());
            if (input.getTelefono() != null) config.setTelefono(input.getTelefono());
            if (input.getDireccion() != null) config.setDireccion(input.getDireccion());
            if (input.getMoneda() != null) config.setMoneda(input.getMoneda());
            if (input.getSimboloMoneda() != null) config.setSimboloMoneda(input.getSimboloMoneda());
            if (input.getPorcentajeIva() != null) config.setPorcentajeIva(input.getPorcentajeIva());
            if (input.getIvaIncluido() != null) config.setIvaIncluido(input.getIvaIncluido());
            if (input.getPermitirDescuentos() != null) config.setPermitirDescuentos(input.getPermitirDescuentos());
            if (input.getMensajeTicket() != null) config.setMensajeTicket(input.getMensajeTicket());
        }
        return configuracionRepository.save(config);
    }
}
