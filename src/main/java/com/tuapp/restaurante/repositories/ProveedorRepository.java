package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Proveedor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Repositorio JPA para la entidad Proveedor.
 * Hereda los métodos CRUD completos de JpaRepository.
 */
@Repository
public interface ProveedorRepository extends JpaRepository<Proveedor, Long> {

    java.util.List<Proveedor> findByActivoTrue();

    /**
     * Busca proveedores por nombre de empresa (útil para búsquedas).
     * Generado automáticamente por Spring Data JPA.
     */
    boolean existsByNombreEmpresa(String nombreEmpresa);
}
