package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Mesa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface MesaRepository extends JpaRepository<Mesa, Long> {
    List<Mesa> findAllByOrderByNumeroAsc();
    List<Mesa> findByEstado(String estado);
    Optional<Mesa> findByNumero(Integer numero);
    List<Mesa> findByActivoTrueOrderByNumeroAsc();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Mesa m where m.id = :id")
    Optional<Mesa> findByIdForUpdate(@Param("id") Long id);
}
