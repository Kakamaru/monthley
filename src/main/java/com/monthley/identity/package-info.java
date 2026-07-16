/** Identity — app_user, sp_membership, platform_admin, JWT, pengesahan e-mel. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Identity",
        allowedDependencies = { "shared", "notification::api" })
package com.monthley.identity;
