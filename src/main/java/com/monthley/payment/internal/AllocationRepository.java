package com.monthley.payment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

interface AllocationRepository extends JpaRepository<PaymentAllocation, Long> {
}
