package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.repositories.InsumoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class InsumoService {

    private final InsumoRepository insumoRepository;

    public InsumoService(InsumoRepository insumoRepository) {
        this.insumoRepository = insumoRepository;
    }

    @Transactional(readOnly = true)
    public List<Insumo> listarTodos() {
        return insumoRepository.findByActivoTrueOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Insumo> buscarPorId(Long id) {
        return insumoRepository.findById(id);
    }

    @Transactional
    public Insumo guardar(Insumo insumo) {
        if (insumo.getId() == null) {
            insumo.setStockActual(LegacyInventoryPrecision.forStorage(insumo.getStockActual()));
            return insumoRepository.save(insumo);
        }
        // El formulario de edición envía el stock mostrado como campo oculto.
        // Nunca usar ese saldo, pues puede haber una venta posterior a la carga del formulario.
        Insumo current = insumoRepository.lockById(insumo.getId())
                .orElseThrow(() -> new IllegalArgumentException("Insumo no encontrado"));
        current.setNombre(insumo.getNombre());
        current.setUnidadMedida(insumo.getUnidadMedida());
        current.setCostoUnitario(insumo.getCostoUnitario());
        current.setStockMinimo(insumo.getStockMinimo());
        current.setCategoria(insumo.getCategoria());
        current.setProveedor(insumo.getProveedor());
        return current;
    }

    @Transactional
    public void eliminar(Long id) {
        insumoRepository.lockById(id).ifPresent(insumo -> {
            insumo.setActivo(false);
            insumoRepository.save(insumo);
        });
    }
}
