package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Proveedor;
import com.tuapp.restaurante.repositories.ProveedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Servicio de Proveedores con lógica CRUD básica.
 */
@Service
public class ProveedorService {


    public ProveedorService(ProveedorRepository proveedorRepository) {
        this.proveedorRepository = proveedorRepository;
    }

    private final ProveedorRepository proveedorRepository;

    /** Devuelve todos los proveedores. */
    @Transactional(readOnly = true)
    public List<Proveedor> listarTodos() {
        return proveedorRepository.findByActivoTrue();
    }

    /** Busca un proveedor por su ID. */
    @Transactional(readOnly = true)
    public Optional<Proveedor> buscarPorId(Long id) {
        return proveedorRepository.findById(id);
    }

    /**
     * Guarda o actualiza un proveedor.
     * Si el objeto tiene ID → UPDATE. Si no tiene ID → INSERT.
     */
    @Transactional
    public Proveedor guardar(Proveedor proveedor) {
        return proveedorRepository.save(proveedor);
    }

    /** Elimina un proveedor por su ID. */
    @Transactional
    public void eliminar(Long id) {
        proveedorRepository.findById(id).ifPresent(p -> {
            p.setActivo(false);
            proveedorRepository.save(p);
        });
    }
}
