package com.tuapp.restaurante.services;

import com.tuapp.restaurante.models.SubReceta;
import com.tuapp.restaurante.repositories.InsumoRepository;
import com.tuapp.restaurante.repositories.SubRecetaRepository;
import com.tuapp.restaurante.security.AccessPolicy;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized composition mutations; no stock is changed here. */
@Service
public class SubRecetaService {
    private final InsumoRepository insumos;
    private final SubRecetaRepository recipes;
    private final AccessPolicy access;

    public SubRecetaService(InsumoRepository insumos, SubRecetaRepository recipes, AccessPolicy access) {
        this.insumos = insumos;
        this.recipes = recipes;
        this.access = access;
    }

    @Transactional
    public void agregar(Long destinationId, Long ingredientId, BigDecimal amount) {
        access.requireManagement();
        var destination = insumos.findById(destinationId)
                .orElseThrow(() -> new IllegalArgumentException("Insumo destino no encontrado"));
        var ingredient = insumos.findById(ingredientId)
                .orElseThrow(() -> new IllegalArgumentException("Ingrediente no encontrado"));
        if (destination.getIngredientesPreparacion().stream()
                .anyMatch(line -> line.getIngrediente().getId().equals(ingredientId))) {
            throw new IllegalArgumentException("El ingrediente ya es parte de la composición.");
        }
        SubReceta recipe = new SubReceta();
        recipe.setInsumoDestino(destination);
        recipe.setIngrediente(ingredient);
        recipe.setCantidad(amount);
        recipes.save(recipe);
        destination.getIngredientesPreparacion().add(recipe);
    }

    @Transactional
    public void quitar(Long destinationId, Long recipeId) {
        access.requireManagement();
        var destination = insumos.findById(destinationId)
                .orElseThrow(() -> new IllegalArgumentException("Insumo destino no encontrado"));
        var recipe = recipes.findById(recipeId)
                .orElseThrow(() -> new IllegalArgumentException("Ingrediente no encontrado"));
        if (!recipe.getInsumoDestino().getId().equals(destinationId)) {
            throw new IllegalArgumentException("El ingrediente no pertenece a esta composición");
        }
        destination.getIngredientesPreparacion().removeIf(line -> line.getId().equals(recipeId));
        recipes.delete(recipe);
    }
}
