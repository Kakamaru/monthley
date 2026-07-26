/** Account — akaun pelanggan + langganan produk. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Account",
        allowedDependencies = { "shared", "tenancy::api", "notification::api",
                                "statement::api" })
package com.monthley.account;
