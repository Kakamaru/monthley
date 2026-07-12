package com.monthley;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Monthley — platform bil & kutipan berulang (multi-tenant SaaS).
 *
 * Modul perniagaan = sub-package terus di bawah com.monthley.
 * Sempadan di-enforce oleh Spring Modulith (lihat ModularityTests).
 */
@Modulith(systemName = "Monthley")
@SpringBootApplication
@EnableScheduling
public class MonthleyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MonthleyApplication.class, args);
    }
}
