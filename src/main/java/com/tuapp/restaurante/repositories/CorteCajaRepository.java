package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.CorteCaja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CorteCajaRepository extends JpaRepository<CorteCaja, Long> {
    List<CorteCaja> findAllByOrderByFechaAperturaDesc();
    Optional<CorteCaja> findFirstByEstadoOrderByFechaAperturaDesc(String estado);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CorteCaja c where c.estado = 'ABIERTO'")
    Optional<CorteCaja> lockOpenShift();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CorteCaja c where c.id = :id")
    Optional<CorteCaja> lockById(@Param("id") Long id);
    List<CorteCaja> findByFechaAperturaBetween(LocalDateTime start, LocalDateTime end);
}
