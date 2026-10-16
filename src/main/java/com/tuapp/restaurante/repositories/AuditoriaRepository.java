package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Auditoria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {
    List<Auditoria> findAllByOrderByFechaDesc();
    List<Auditoria> findByUsuario(String usuario);
}
