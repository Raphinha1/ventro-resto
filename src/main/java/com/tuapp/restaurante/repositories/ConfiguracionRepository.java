package com.tuapp.restaurante.repositories;

import com.tuapp.restaurante.models.ConfiguracionSistema;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfiguracionRepository extends JpaRepository<ConfiguracionSistema, Long> {
}
