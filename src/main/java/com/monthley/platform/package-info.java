/** Platform — superadmin: onboard SP, urus pelan & entitlement. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Platform Admin",
        allowedDependencies = { "shared", "ledger :: api", "account :: api" })
package com.monthley.platform;
