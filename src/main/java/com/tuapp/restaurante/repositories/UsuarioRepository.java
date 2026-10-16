package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA para la entidad Usuario.
 *
 * @Repository → Marca esta interfaz como componente de acceso a datos.
 * JpaRepository<Usuario, Long> provee automáticamente:
 *   - findAll(), findById(), save(), deleteById(), count(), etc.
 *
 * Spring Data JPA genera la implementación en tiempo de ejecución.
 */
@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    java.util.Optional<Usuario> findByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.id = :id")
    Optional<Usuario> findByIdForUpdate(@Param("id") Long id);

    /**
     * Busca todos los usuarios que están activos.
     */
    List<Usuario> findByActivoTrue();

    /**
     * Verifica si ya existe un usuario con ese username (para validación).
     */
    boolean existsByUsername(String username);

    long countByActivoTrue();

    boolean existsByPinDigest(String pinDigest);

    boolean existsByPinDigestAndIdNot(String pinDigest, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.pinDigest = :digest and u.activo = true")
    Optional<Usuario> lockActiveByPinDigest(@Param("digest") String digest);
}
