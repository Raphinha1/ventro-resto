package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Reserva;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;

@Repository
public interface ReservaRepository extends JpaRepository<Reserva, Long> {
    boolean existsByMesaId(Long mesaId);

    @Query("select coalesce(sum(r.numeroPersonas), 0) from Reserva r where r.fechaHora between :desde and :hasta")
    Long sumarPersonasEntre(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    @Query(value = "select cast(fecha_hora as date) as dia, cast(coalesce(sum(numero_personas),0) as bigint) as comensales " +
            "from reservas where fecha_hora between :desde and :hasta " +
            "and cast(fecha_hora as time) between cast(:desde as time) and cast(:hasta as time) " +
            "group by cast(fecha_hora as date)", nativeQuery = true)
    List<ReservationDailyProjection> sumarPersonasPorDia(@Param("desde") LocalDateTime desde,
                                                         @Param("hasta") LocalDateTime hasta);
    
    // Buscar reservas entre fechas (para vista de calendario)
    List<Reserva> findByFechaHoraBetweenOrderByFechaHoraAsc(LocalDateTime start, LocalDateTime end);
    
    // Buscar reservas de un cliente
    List<Reserva> findByClienteIdOrderByFechaHoraDesc(Long clienteId);
    
    // Reservas pendientes/confirmadas a partir de ahora
    @Query("SELECT r FROM Reserva r WHERE r.fechaHora >= :ahora AND r.estado IN ('PENDIENTE', 'CONFIRMADA') ORDER BY r.fechaHora ASC")
    List<Reserva> findProximasActivas(LocalDateTime ahora);
    
    // Para validación de mesa ocupada (solapamiento simple)
    @Query("SELECT r FROM Reserva r WHERE r.mesa.id = :mesaId " +
           "AND r.estado IN ('CONFIRMADA', 'EN_CURSO') " +
           "AND r.fechaHora >= :desde AND r.fechaHora <= :hasta")
    List<Reserva> findConflictosMesa(Long mesaId, LocalDateTime desde, LocalDateTime hasta);

    // Scalar read deliberately avoids returning a possibly cached managed Reserva.
    @Query("select r.mesa.id from Reserva r where r.id = :id")
    Long findMesaId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reserva r where r.id = :id")
    Optional<Reserva> findByIdForUpdate(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Reserva r SET r.mesa = null WHERE r.mesa.id = :mesaId")
    void desvincularMesa(Long mesaId);
}
