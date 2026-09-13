@ApplicationModule(
        allowedDependencies = {
            "consent :: api",
            "identity :: api",
            "orchestration :: api",
            "registry :: api",
            "shared",
            "tracking :: api"
        })
package com.samanvay.notifications;

import org.springframework.modulith.ApplicationModule;
