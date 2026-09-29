@ApplicationModule(
        allowedDependencies = {
            "audit :: api",
            "orchestration :: api",
            "shared",
            "tracking :: api"
        })
package com.samanvay.payments;

import org.springframework.modulith.ApplicationModule;
