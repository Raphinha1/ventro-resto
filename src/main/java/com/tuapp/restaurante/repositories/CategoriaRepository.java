package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Categoria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA para la entidad Categoria.
 * Hereda los métodos CRUD completos de JpaRepository.
 * No requiere implementación adicional para operaciones básicas.
 */
@Repository
public interface CategoriaRepository extends JpaRepository<Categoria, Long> {

    /**
     * Búsqueda por nombre (útil para evitar duplicados).
     * Consulta generada: SELECT * FROM categoria WHERE nombre = ?
     */
    boolean existsByNombreAndTipo(String nombre, String tipo);
    java.util.List<Categoria> findByActivoTrue();
    java.util.List<Categoria> findByTipoAndActivoTrue(String tipo);
}
