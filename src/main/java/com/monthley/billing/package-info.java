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
                "document::api" })
package com.monthley.billing;
