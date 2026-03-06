package com.javaducker.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JavaDuckerServerApp {

    public static void main(String[] args) {
        SpringApplication.run(JavaDuckerServerApp.class, args);
    }
}
