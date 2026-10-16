package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Categoria;
import com.tuapp.restaurante.repositories.CategoriaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Servicio de Categorías con lógica CRUD básica.
 *
 * @Service      → Componente de capa de servicio (lógica de negocio).
 * @Transactional → Gestión automática de transacciones.
 */
@Service
public class CategoriaService {


    public CategoriaService(CategoriaRepository categoriaRepository) {
        this.categoriaRepository = categoriaRepository;
    }

    private final CategoriaRepository categoriaRepository;

    /** Devuelve todas las categorías ordenadas por nombre. */
    @Transactional(readOnly = true)
    public List<Categoria> listarTodas() {
        return categoriaRepository.findByActivoTrue();
    }

    /** Busca una categoría por su ID. */
    @Transactional(readOnly = true)
    public Optional<Categoria> buscarPorId(Long id) {
        return categoriaRepository.findById(id);
    }

    /**
     * Guarda o actualiza una categoría.
     * Si el objeto tiene ID → UPDATE. Si no tiene ID → INSERT.
     */
    @Transactional
    public Categoria guardar(Categoria categoria) {
        return categoriaRepository.save(categoria);
    }

    /** Elimina una categoría por su ID. */
    @Transactional
    public void eliminar(Long id) {
        categoriaRepository.findById(id).ifPresent(c -> {
            c.setActivo(false);
            categoriaRepository.save(c);
        });
    }

    @Transactional(readOnly = true)
    public List<Categoria> listarPorTipo(String tipo) {
        return categoriaRepository.findByTipoAndActivoTrue(tipo);
    }

    /** Verifica si ya existe una categoría con el mismo nombre y tipo. */
    @Transactional(readOnly = true)
    public boolean existePorNombreYTipo(String nombre, String tipo) {
        return categoriaRepository.existsByNombreAndTipo(nombre, tipo);
    }
}
