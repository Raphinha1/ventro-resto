package com.tuapp.restaurante.models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.Duration;

@Entity
@Table(name = "asistencia")
public class Asistencia implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "hora_entrada", nullable = false)
    private LocalDateTime horaEntrada;

    @Column(name = "hora_salida")
    private LocalDateTime horaSalida;

    @Column(name = "horas_trabajadas")
    private Double horasTrabajadas;

    public Asistencia() {
    }

    public Asistencia(Usuario usuario, LocalDateTime horaEntrada) {
        this.usuario = usuario;
        this.horaEntrada = horaEntrada;
    }

    public void marcarSalida(LocalDateTime horaSalida) {
        this.horaSalida = horaSalida;
        if (this.horaEntrada != null && this.horaSalida != null) {
            Duration d = Duration.between(this.horaEntrada, this.horaSalida);
            this.horasTrabajadas = d.toMinutes() / 60.0;
        }
    }

    // Getters y Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public LocalDateTime getHoraEntrada() { return horaEntrada; }
    public void setHoraEntrada(LocalDateTime horaEntrada) { this.horaEntrada = horaEntrada; }
    public LocalDateTime getHoraSalida() { return horaSalida; }
    public void setHoraSalida(LocalDateTime horaSalida) { this.horaSalida = horaSalida; }
    public Double getHorasTrabajadas() { return horasTrabajadas; }
    public void setHorasTrabajadas(Double horasTrabajadas) { this.horasTrabajadas = horasTrabajadas; }
}
