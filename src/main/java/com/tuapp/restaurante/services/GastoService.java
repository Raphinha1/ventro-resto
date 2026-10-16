package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Gasto;
import com.tuapp.restaurante.repositories.GastoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class GastoService {

    private final GastoRepository gastoRepository;

    public GastoService(GastoRepository gastoRepository) {
        this.gastoRepository = gastoRepository;
    }

    @Transactional(readOnly = true)
    public List<Gasto> listarActivos() {
        return gastoRepository.findByActivoTrueOrderByFechaDesc();
    }

    @Transactional(readOnly = true)
    public Optional<Gasto> buscarPorId(Long id) {
        return gastoRepository.findById(id);
    }

    @Transactional
    public Gasto guardar(Gasto gasto) {
        return gastoRepository.save(gasto);
    }

    @Transactional
    public void eliminarLogico(Long id) {
        gastoRepository.findById(id).ifPresent(g -> {
            g.setActivo(false);
            gastoRepository.save(g);
        });
    }

    @Transactional(readOnly = true)
    public List<Gasto> listarTodosActivos() {
        return gastoRepository.findByActivoTrue();
    }
}
