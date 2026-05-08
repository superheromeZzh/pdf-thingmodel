package com.example.pdftm;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.pdftm.mapper")
public class PdfThingModelApplication {
    public static void main(String[] args) {
        SpringApplication.run(PdfThingModelApplication.class, args);
    }
}
