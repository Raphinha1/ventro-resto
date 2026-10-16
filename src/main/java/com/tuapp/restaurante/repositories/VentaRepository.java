package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.Venta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface VentaRepository extends JpaRepository<Venta, Long> {
    @Query("select coalesce(sum(v.total), 0) from Venta v")
    java.math.BigDecimal sumTotal();

    @Query(value = "select cast(fecha as date) as dia, coalesce(sum(total), 0) as total " +
            "from ventas where fecha between :desde and :hasta group by cast(fecha as date)", nativeQuery = true)
    List<DailySalesProjection> sumarPorDia(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    @Query(value = "select p.nombre as nombre, cast(sum(d.cantidad) as bigint) as cantidad " +
            "from ventas v join venta_detalles d on d.venta_id=v.id join producto p on p.id=d.producto_id " +
            "where v.fecha between :desde and :hasta group by p.nombre", nativeQuery = true)
    List<ProductQuantityProjection> cantidadesPorProducto(@Param("desde") LocalDateTime desde,
                                                          @Param("hasta") LocalDateTime hasta);

    @Query(value = "with costos as (select r.producto_id, sum(r.cantidad*i.costo_unitario) as costo " +
            "from receta r join insumo i on i.id=r.insumo_id group by r.producto_id) " +
            "select p.id as \"productoId\",p.nombre as nombre,p.precio_venta as precio, " +
            "cast(sum(d.cantidad) as bigint) as cantidad,coalesce(c.costo,0) as costo " +
            "from ventas v join venta_detalles d on d.venta_id=v.id join producto p on p.id=d.producto_id " +
            "left join costos c on c.producto_id=p.id where v.fecha between :desde and :hasta " +
            "group by p.id,p.nombre,p.precio_venta,c.costo", nativeQuery = true)
    List<ProductPerformanceProjection> rendimientoProductos(@Param("desde") LocalDateTime desde,
                                                             @Param("hasta") LocalDateTime hasta);

    @Query(value = "select cast(extract(dow from fecha) as integer)+1 as \"diaSemana\", coalesce(sum(total),0) as total, " +
            "cast(count(*) as bigint) as cuenta from ventas where fecha >= :desde " +
            "and cast(extract(dow from fecha) as integer)+1 in (1,4,6,7) " +
            "group by cast(extract(dow from fecha) as integer)+1", nativeQuery = true)
    List<WeekdaySalesProjection> resumenPorDiaSemana(@Param("desde") LocalDateTime desde);

    @Query(value = "select coalesce(sum(total),0) as total,cast(count(*) as bigint) as cuenta from ventas " +
            "where fecha between :desde and :hasta", nativeQuery = true)
    SalesPeriodTotals totalesPeriodo(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    List<Venta> findByOrderByFechaDesc();
    List<Venta> findByFechaBetweenOrderByFechaDesc(LocalDateTime start, LocalDateTime end);
    List<Venta> findByCorteCajaIdOrderByFechaDesc(Long corteCajaId);

    // Compatibilidad del contador de tickets: los canjes sin cobro aún no tienen contrato de turno.
    @Query(value = "select count(*) from ventas where total = 0 and corte_caja_id is null " +
            "and fecha between :desde and :hasta", nativeQuery = true)
    long contarSinCobroEnPeriodo(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

}
