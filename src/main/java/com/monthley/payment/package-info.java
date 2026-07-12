/**
 * Payment — peruntukan FIFO (knock-off), resit sebagai dokumen, pembatalan contra.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Payment & Allocation",
        allowedDependencies = { "shared", "ledger::api", "document::api" })
package com.monthley.payment;
