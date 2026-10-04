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
import org.springframework.core.env.Environment;
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

    /** Refuses to start on dev defaults or weak settings unless {@code department.demo-mode=true}. */
    @Bean
    StartupChecks startupChecks(PortalProperties p, Environment env) {
        return new StartupChecks(p, env);
    }

    @Bean
    RequestHygieneFilter requestHygieneFilter() {
        return new RequestHygieneFilter();
    }

    @Bean
    SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    /** Shared by the portal and the department's own /login, so guessing at one is counted against the other. */
    @Bean
    @ConditionalOnMissingBean
    SignInThrottle signInThrottle(Clock clock) {
        return new SignInThrottle(clock);
    }

    @Bean
    PortalSession portalSession(PortalProperties p) {
        return PortalSession.withSecretFile(p.sessionSecret(), p.deptCode(), Path.of(p.manifestKeyFile()).toAbsolutePath().resolveSibling("portal-session.secret"));
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
            CitizenDirectory directory, HomeAssertions home, ConsentSigner signer, SignInThrottle throttle, Clock clock) {
        return new PortalController(p, sessions, samanvay, catalog, directory, home, signer, throttle, clock);
    }
}
