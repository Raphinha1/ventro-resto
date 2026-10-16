package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.SubReceta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubRecetaRepository extends JpaRepository<SubReceta, Long> {
}
