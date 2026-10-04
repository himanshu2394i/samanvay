package in.samanvay.departments.kit;

import com.nimbusds.jose.jwk.ECKey;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

/**
 * Switches the portal on for a department that provides a {@link CitizenDirectory} and {@link HomeAssertions} (and a
 * {@code journeys.json} on its classpath). Everything else comes from {@code portal.*}.
 */
@AutoConfiguration
@EnableConfigurationProperties(PortalProperties.class)
@ConditionalOnBean({CitizenDirectory.class, HomeAssertions.class})
public class KitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock portalClock() {
        return Clock.systemUTC();
    }

    @Bean
    PortalSession portalSession(PortalProperties p) {
        return PortalSession.withSecretFile(p.sessionSecret(), Path.of(p.manifestKeyFile()).toAbsolutePath().resolveSibling("portal-session.secret"));
    }

    @Bean
    SamanvayClient samanvayClient(PortalProperties p, Clock clock) {
        return new SamanvayClient(p.samanvay(), clock);
    }

    @Bean
    JourneyCatalog journeyCatalog(PortalProperties p) throws IOException {
        return new JourneyCatalog(p.deptCode(), new ClassPathResource("journeys.json").getInputStream());
    }

    @Bean
    ConsentSigner consentSigner(PortalProperties p) {
        ECKey key = ManifestKey.loadOrCreate(Path.of(p.manifestKeyFile()));
        return new ConsentSigner(key, p.deptCode());
    }

    @Bean
    PortalPages portalPages() {
        return new PortalPages();
    }

    @Bean
    PortalController portalController(PortalProperties p, PortalSession sessions, SamanvayClient samanvay, JourneyCatalog catalog,
            CitizenDirectory directory, HomeAssertions home, ConsentSigner signer, Clock clock) {
        return new PortalController(p, sessions, samanvay, catalog, directory, home, signer, clock);
    }
}
