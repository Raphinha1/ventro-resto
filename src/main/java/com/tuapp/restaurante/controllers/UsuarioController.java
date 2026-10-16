package com.tuapp.restaurante.controllers;

import com.tuapp.restaurante.models.Usuario;
import com.tuapp.restaurante.dto.CreateUsuarioRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.tuapp.restaurante.services.UsuarioService;
import com.tuapp.restaurante.services.ConfiguracionService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/usuarios")
public class UsuarioController {


    public UsuarioController(UsuarioService usuarioService, ConfiguracionService configuracionService) {
        this.usuarioService = usuarioService;
        this.configuracionService = configuracionService;
    }

    private final UsuarioService usuarioService;
    private final ConfiguracionService configuracionService;

    /** GET /usuarios → Lista todos los usuarios. */
    @GetMapping
    public String listar(Model model) {
        model.addAttribute("usuarios", usuarioService.listarTodos());
        model.addAttribute("config", configuracionService.obtener());
        model.addAttribute("titulo", "Gestión de Usuarios");
        return "usuarios/lista";
    }

    /** GET /usuarios/nuevo → Formulario de creación. */
    @GetMapping("/nuevo")
    public String mostrarFormularioNuevo(Model model) {
        model.addAttribute("usuario", new Usuario());
        model.addAttribute("titulo", "Nuevo Usuario");
        model.addAttribute("esNuevo", true);
        return "usuarios/formulario";
    }

    /** POST /usuarios/guardar → Crea un nuevo usuario (encripta contraseña). */
    @PostMapping("/guardar")
    public String guardar(@Valid @ModelAttribute("usuario") CreateUsuarioRequest usuario,
            BindingResult resultado,
            @RequestParam(value = "id", required = false) String creationId,
            @RequestParam(value = "pinNuevo", required = false) String pinNuevo,
            RedirectAttributes flash,
            Model model,
            jakarta.servlet.http.HttpServletRequest request) {

        if (creationId != null && !creationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El alta no acepta identificadores");
        }

        if (resultado.hasErrors()) {
            String errores = resultado.getAllErrors().stream()
                    .map(org.springframework.validation.ObjectError::getDefaultMessage)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(" "));
            flash.addFlashAttribute("mensajeError", errores.isBlank()
                    ? "Revisa los datos del usuario e inténtalo de nuevo."
                    : "No se pudo crear el usuario: " + errores);
            return "redirect:/usuarios";
        }

        try {
            usuarioService.registrarUsuario(usuario, pinNuevo);
            flash.addFlashAttribute("mensajeExito", "Usuario creado exitosamente.");
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error: " + e.getMessage());
        }

        return "redirect:/usuarios";
    }

    /** GET /usuarios/editar/{id} → Formulario de edición pre-llenado. */
    @GetMapping("/editar/{id}")
    public String mostrarFormularioEditar(@PathVariable Long id,
            Model model,
            RedirectAttributes flash) {
        return usuarioService.buscarPorId(id)
                .map(usuario -> {
                    // Limpiamos la contraseña para no mostrarla en el form
                    usuario.setPassword("");
                    model.addAttribute("usuario", usuario);
                    model.addAttribute("titulo", "Editar Usuario");
                    model.addAttribute("esNuevo", false);
                    return "usuarios/formulario";
                })
                .orElseGet(() -> {
                    flash.addFlashAttribute("mensajeError", "Usuario no encontrado con ID: " + id);
                    return "redirect:/usuarios";
                });
    }

    /** POST /usuarios/actualizar → Guarda los cambios del usuario editado. */
    @PostMapping("/actualizar")
    public String actualizar(@ModelAttribute("usuario") Usuario usuario,
            @RequestParam(value = "nuevaPassword", required = false) String nuevaPassword,
            @RequestParam(value = "pinNuevo", required = false) String pinNuevo,
            @RequestParam(value = "revocarPin", defaultValue = "false") boolean revocarPin,
            RedirectAttributes flash,
            Model model,
            jakarta.servlet.http.HttpServletRequest request) {
        
        try {
            usuarioService.actualizar(usuario, nuevaPassword, pinNuevo, revocarPin);
            flash.addFlashAttribute("mensajeExito", "Usuario actualizado exitosamente.");
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error: " + e.getMessage());
        }
        return "redirect:/usuarios";
    }

    /** POST /usuarios/eliminar/{id} → Elimina un usuario. */
    @PostMapping("/eliminar/{id}")
    public String eliminar(@PathVariable Long id, RedirectAttributes flash, jakarta.servlet.http.HttpServletRequest request) {
        try {
            usuarioService.eliminar(id);
            flash.addFlashAttribute("mensajeExito", "Usuario eliminado exitosamente.");
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            flash.addFlashAttribute("mensajeError", "Error al eliminar: " + e.getMessage());
        }
        return "redirect:/usuarios";
    }
}
