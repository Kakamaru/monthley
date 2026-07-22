package com.monthley.payment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findBySpCodeAndIdempotencyKey(String spCode, String idempotencyKey);
}
