@ApplicationModule(
        allowedDependencies = {
            "consent :: api",
            "identity :: api",
            "orchestration :: api",
            "registry :: api",
            "tracking :: api"
        })
package com.samanvay.notifications;

import org.springframework.modulith.ApplicationModule;
