/** Platform — superadmin: cipta / luluskan / suspend Service Provider. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Platform Admin",
        allowedDependencies = { "shared", "tenancy::api", "identity::api" })
package com.monthley.platform;
