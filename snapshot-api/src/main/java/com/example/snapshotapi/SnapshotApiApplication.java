package com.example.snapshotapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SnapshotApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(SnapshotApiApplication.class, args);
    }
}