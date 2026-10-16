package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Producto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductoRepository extends JpaRepository<Producto, Long> {
    @EntityGraph(attributePaths = {"categoria", "ingredientes", "ingredientes.insumo", "insumoReventa"})
    java.util.List<Producto> findByActivoTrue();
    long countByActivoTrue();
}
