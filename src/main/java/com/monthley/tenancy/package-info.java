/** Tenancy — service_provider (sp_code = tenant), settings, membership. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Tenancy",
        allowedDependencies = { "shared", "identity::api" })
package com.monthley.tenancy;
