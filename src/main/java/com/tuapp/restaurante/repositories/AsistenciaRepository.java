package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Asistencia;
import com.tuapp.restaurante.models.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface AsistenciaRepository extends JpaRepository<Asistencia, Long> {
    Optional<Asistencia> findTopByUsuarioAndHoraSalidaIsNullOrderByHoraEntradaDesc(Usuario usuario);
    List<Asistencia> findByOrderByHoraEntradaDesc();
    List<Asistencia> findByUsuarioOrderByHoraEntradaDesc(Usuario usuario);
}
