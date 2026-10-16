package com.tuapp.restaurante;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.TimeZone;

@SpringBootApplication
public class RestauranteApplication {

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Monterrey"));
    }

    public static void main(String[] args) {
        SpringApplication.run(RestauranteApplication.class, args);
    }

}
