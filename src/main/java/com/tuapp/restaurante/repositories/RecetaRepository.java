package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Receta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecetaRepository extends JpaRepository<Receta, Long> {
    List<Receta> findByProductoId(Long productoId);
}
