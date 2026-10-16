package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Producto;
import com.tuapp.restaurante.models.Receta;
import com.tuapp.restaurante.repositories.ProductoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class ProductoService {


    public ProductoService(ProductoRepository productoRepository, ConfiguracionService configuracionService) {
        this.productoRepository = productoRepository;
        this.configuracionService = configuracionService;
    }

    private final ProductoRepository productoRepository;
    private final ConfiguracionService configuracionService;

    @Transactional(readOnly = true)
    public List<Producto> listarTodos() {
        return productoRepository.findByActivoTrue();
    }

    @Transactional(readOnly = true)
    public Optional<Producto> buscarPorId(Long id) {
        return productoRepository.findById(id);
    }

    @Transactional
    public Producto guardar(Producto producto) {
        if (producto.getId() != null) {
            // Es una edición: cargamos el existente para no perder la colección de ingredientes
            return productoRepository.findById(producto.getId()).map(existente -> {
                // Si el producto estaba inactivo y se intenta activar, validar límite
                if (!existente.isActivo() && producto.isActivo()) {
                    validarLimiteProductos();
                }
                
                existente.setNombre(producto.getNombre());
                existente.setPrecioVenta(producto.getPrecioVenta());
                existente.setDescripcion(producto.getDescripcion());
                existente.setCategoria(producto.getCategoria());
                existente.setActivo(producto.isActivo());
                existente.setImagenUrl(producto.getImagenUrl());
                existente.setPuntosOtorgados(producto.getPuntosOtorgados());
                existente.setPuntosPrecio(producto.getPuntosPrecio());
                // NO tocamos la lista de ingredientes aquí para evitar el error de orphanRemoval
                return productoRepository.save(existente);
            }).orElseGet(() -> {
                if (producto.isActivo()) validarLimiteProductos();
                return productoRepository.save(producto);
            });
        }
        
        // Es un registro nuevo
        if (producto.isActivo()) {
            validarLimiteProductos();
        }
        return productoRepository.save(producto);
    }

    private void validarLimiteProductos() {
        long activos = productoRepository.countByActivoTrue();
        com.tuapp.restaurante.models.ConfiguracionSistema cfg = configuracionService.obtener();
        if (activos >= cfg.getLimiteProductos()) {
            throw new IllegalStateException("Has alcanzado el límite de productos activos para tu plan (" + cfg.getLimiteProductos() + "). Desactiva algún producto o mejora tu plan.");
        }
    }

    @Transactional
    public void eliminar(Long id) {
        productoRepository.findById(id).ifPresent(p -> {
            p.setActivo(false);
            productoRepository.save(p);
        });
    }
    
    // Método para borrado físico si se necesitara (opcional)
    @Transactional
    public void eliminarPermanente(Long id) {
        productoRepository.deleteById(id);
    }

    /**
     * Calcula el costo total de un producto multiplicando 
     * el costo unitario de cada insumo por la cantidad en la receta
     */
    @Transactional(readOnly = true)
    public BigDecimal calcularCostoProduccion(Long id) {
        Producto producto = productoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
                
        BigDecimal costoTotal = BigDecimal.ZERO;
        if (producto.getIngredientes() != null) {
            for (Receta r : producto.getIngredientes()) {
                BigDecimal cantidad = r.getCantidad();
                BigDecimal costoInsumo = r.getInsumo().getCostoUnitario();
                costoTotal = costoTotal.add(cantidad.multiply(costoInsumo));
            }
        }
        return costoTotal;
    }
}
