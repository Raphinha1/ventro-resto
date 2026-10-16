package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.Receta;
import com.tuapp.restaurante.repositories.RecetaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecetaService {


    public RecetaService(RecetaRepository recetaRepository) {
        this.recetaRepository = recetaRepository;
    }

    private final RecetaRepository recetaRepository;

    @Transactional
    public Receta agregarIngrediente(Receta receta) {
        return recetaRepository.save(receta);
    }

    @Transactional
    public void eliminarIngrediente(Long id) {
        recetaRepository.deleteById(id);
    }
}
