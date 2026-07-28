package com.paynova;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PayNova Escrow — portfolio-grade sandbox escrow payment platform.
 * Does not process or custody real funds.
 *
 * Source of Truth: the PayNova Escrow detailed design document v1.2 (under docs/).
 */
@SpringBootApplication
@EnableScheduling
public class PayNovaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayNovaApplication.class, args);
    }
}
