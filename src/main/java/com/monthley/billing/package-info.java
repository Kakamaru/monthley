/**
 * Billing — jana invois berulang. Cipta dokumen (document) + post ke ledger.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Billing Engine",
        allowedDependencies = {
                "shared",
                "ledger::api",
                "catalog::api",
                "account::api",
                "document::api",
                "tenancy::api",
                "payment::api" })   // AdvancePort — knock advance semasa jana bil (ADR 0009)
package com.monthley.billing;
