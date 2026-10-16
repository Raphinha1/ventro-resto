package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.dto.CreateUsuarioRequest;
import com.tuapp.restaurante.security.AccessPolicy;
import com.tuapp.restaurante.security.ApplicationRole;
import com.tuapp.restaurante.models.ConfiguracionSistema;
import com.tuapp.restaurante.repositories.UsuarioRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class UsuarioService implements UserDetailsService {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;


    public UsuarioService(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder,
                          ConfiguracionService configuracionService, AttendancePinCodec pinCodec, AccessPolicy access) {
        this.access = access;
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.configuracionService = configuracionService;
        this.pinCodec = pinCodec;
    }

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final ConfiguracionService configuracionService;
    private final AttendancePinCodec pinCodec;
    private final AccessPolicy access;

    /** Carga el usuario por username para Spring Security (login). */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Usuario usuario = usuarioRepository.findByUsername(username)
                .filter(Usuario::isActivo)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Usuario no encontrado o inactivo: " + username));

        String rolConPrefijo;
        try { rolConPrefijo = ApplicationRole.parse(usuario.getRol()).authority(); }
        catch (IllegalArgumentException ex) { throw new UsernameNotFoundException("Rol no permitido"); }
        SimpleGrantedAuthority autoridad = new SimpleGrantedAuthority(rolConPrefijo);

        return new User(
                usuario.getUsername(),
                usuario.getPassword(),
                Collections.singletonList(autoridad));
    }

    /** Registra un nuevo usuario encriptando su contrasena. */
    @Transactional
    public Usuario registrarUsuario(CreateUsuarioRequest input, String pinNuevo) {
        String role = ApplicationRole.parse(input.getRol()).authority();
        access.requireUserChange(null, role);
        Usuario usuario = new Usuario();
        usuario.setUsername(input.getUsername());
        usuario.setPassword(input.getPassword());
        usuario.setNombreCompleto(input.getNombreCompleto());
        usuario.setRol(role);
        usuario.setActivo(input.isActivo());
        if (usuarioRepository.existsByUsername(usuario.getUsername())) {
            throw new IllegalArgumentException(
                    "Ya existe un usuario con el username: " + usuario.getUsername());
        }

        // Validar límite de usuarios activos en el plan (solo si se intenta registrar como activo)
        if (usuario.isActivo()) {
            long activos = usuarioRepository.countByActivoTrue();
            ConfiguracionSistema cfg = configuracionService.obtener();
            if (activos >= cfg.getLimiteUsuarios()) {
                throw new IllegalStateException("Límite de usuarios activos alcanzado para tu plan (" + cfg.getLimiteUsuarios() + "). Desactiva un usuario antes de crear uno nuevo.");
            }
        }

        usuario.setPinDigest(null);
        if (pinNuevo != null && !pinNuevo.isBlank()) {
            String digest = pinCodec.pinDigest(pinNuevo);
            if (usuarioRepository.existsByPinDigest(digest)) {
                throw new IllegalArgumentException("El PIN ya está asignado a otro usuario.");
            }
            usuario.setPinDigest(digest);
        }
        usuario.setPassword(passwordEncoder.encode(usuario.getPassword()));
        return saveWithUniquePin(usuario);
    }

    /**
     * Actualiza un usuario existente.
     * Si nuevaPassword viene en blanco, conserva la contrasena actual.
     * Si viene rellena, la re-encripta con BCrypt.
     */
    @Transactional
    public Usuario actualizar(Usuario usuarioEditado, String nuevaPassword,
                              String pinNuevo, boolean revocarPin) {
        access.requireUserManagement();
        Usuario existente = usuarioRepository.findByIdForUpdate(usuarioEditado.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Usuario no encontrado con ID: " + usuarioEditado.getId()));

        // A locking query can return the actor already cached by requireUserManagement.
        // Refresh under the held target lock before inspecting either role (including self edits).
        entities.refresh(existente, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        String role = ApplicationRole.parse(usuarioEditado.getRol()).authority();
        access.requireUserChange(existente, role);

        Optional<Usuario> duplicado = usuarioRepository.findByUsername(usuarioEditado.getUsername());
        if (duplicado.isPresent() && !duplicado.get().getId().equals(usuarioEditado.getId())) {
            throw new IllegalArgumentException(
                    "Ya existe un usuario con el username: " + usuarioEditado.getUsername());
        }

        existente.setUsername(usuarioEditado.getUsername());
        existente.setNombreCompleto(usuarioEditado.getNombreCompleto());
        existente.setRol(role);
        
        // Validar límite si se está activando a un usuario que estaba inactivo
        if (!existente.isActivo() && usuarioEditado.isActivo()) {
            long activos = usuarioRepository.countByActivoTrue();
            ConfiguracionSistema cfg = configuracionService.obtener();
            if (activos >= cfg.getLimiteUsuarios()) {
                throw new IllegalStateException("No se puede activar el usuario. Has alcanzado el límite de usuarios activos (" + cfg.getLimiteUsuarios() + ").");
            }
        }
        
        existente.setActivo(usuarioEditado.isActivo());
        
        if (revocarPin && pinNuevo != null && !pinNuevo.isBlank()) {
            throw new IllegalArgumentException("No se puede asignar y revocar el PIN en la misma operación.");
        }
        if (revocarPin) {
            existente.setPinDigest(null);
        } else if (pinNuevo != null && !pinNuevo.isBlank()) {
            String digest = pinCodec.pinDigest(pinNuevo);
            if (usuarioRepository.existsByPinDigestAndIdNot(digest, existente.getId())) {
                throw new IllegalArgumentException("El PIN ya está asignado a otro usuario.");
            }
            existente.setPinDigest(digest);
        }

        if (nuevaPassword != null && !nuevaPassword.isBlank()) {
            existente.setPassword(passwordEncoder.encode(nuevaPassword));
        }

        return saveWithUniquePin(existente);
    }

    private Usuario saveWithUniquePin(Usuario usuario) {
        try {
            return usuarioRepository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException exception) {
            if (exception.getMostSpecificCause().getMessage().contains("usuario_pin_digest_unique")) {
                throw new IllegalArgumentException("El PIN ya está asignado a otro usuario.");
            }
            throw exception;
        }
    }

    /** Lista todos los usuarios. */
    @Transactional(readOnly = true)
    public List<Usuario> listarTodos() {
        return usuarioRepository.findAll();
    }

    /** Busca un usuario por ID. */
    @Transactional(readOnly = true)
    public Optional<Usuario> buscarPorId(Long id) {
        return usuarioRepository.findById(id);
    }

    /** Busca un usuario por username. */
    @Transactional(readOnly = true)
    public Optional<Usuario> buscarPorUsername(String username) {
        return usuarioRepository.findByUsername(username);
    }

    /** Elimina un usuario por ID. */
    @Transactional
    public void eliminar(Long id) {
        access.requireUserManagement();
        usuarioRepository.findByIdForUpdate(id).ifPresent(u -> {
            entities.refresh(u, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            access.requireUserChange(u, null);
            u.setActivo(false);
            usuarioRepository.save(u);
        });
    }
}
