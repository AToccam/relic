package com.relic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RelicCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelicCoreApplication.class, args);
        System.out.println("========== Relic Core Started Successfully! ==========");
    }
}
