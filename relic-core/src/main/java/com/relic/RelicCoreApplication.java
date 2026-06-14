package com.relic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RelicCoreApplication {

    static {
        System.setProperty("java.net.useSystemProxies",
                System.getProperty("java.net.useSystemProxies", "true"));
    }

    public static void main(String[] args) {
        SpringApplication.run(RelicCoreApplication.class, args);
        System.out.println("========== Relic Core Started Successfully! ==========");
    }
}
