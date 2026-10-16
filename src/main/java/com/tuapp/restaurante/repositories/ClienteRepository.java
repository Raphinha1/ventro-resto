package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Cliente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cliente c where c.id = :id")
    Optional<Cliente> lockById(@Param("id") Long id);

    /** Clientes ordenados por puntos descendente (ranking de lealtad) */
    List<Cliente> findAllByOrderByPuntosDesc();

    /** Todos los clientes VIP */
    List<Cliente> findByTipo(String tipo);

    /** Clientes con saldo de crédito pendiente (deuda) */
    @Query("SELECT c FROM Cliente c WHERE c.activo = true AND c.saldoCredito > 0 ORDER BY c.saldoCredito DESC")
    List<Cliente> findClientesConDeuda();

    /** Búsqueda por nombre o teléfono (útil en POS) */
    List<Cliente> findByNombreContainingIgnoreCaseOrTelefonoContaining(String nombre, String telefono);

    /** Solo clientes activos */
    List<Cliente> findByActivoTrue();
}
