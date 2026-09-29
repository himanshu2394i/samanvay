package com.samanvay.orchestration.internal.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.orchestration.api.WorkflowEngine;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;

/**
 * Docker-free guards for the full-app default mode (Flowable on the classpath, {@code samanvay.workflow.engine}
 * unset). SamanvayApplication component-scans the whole {@code com.samanvay} tree including test classes, so a
 * plain {@code @Configuration} test fixture that defines a DataSource silently replaces the app's real one and
 * breaks every full-context IT (this happened once with the H2 fixtures of the Flowable tests).
 */
class DefaultModeContextGuardTest {

    /**
     * Scans the workflow package like the app does (main + test classes), minus @TestConfiguration.
     * FetchDataDelegate is left out only because it needs the consent/connector beans of the full app.
     */
    @ComponentScan(
            basePackageClasses = InProcessWorkflowEngine.class,
            excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestConfiguration.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = FetchDataDelegate.class)
            })
    static class ScanWorkflowPackage {}

    @Test
    void scanningTheWorkflowPackageByDefaultYieldsOnlyTheInProcessEngineAndNoDataSource() {
        new ApplicationContextRunner().withUserConfiguration(ScanWorkflowPackage.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(WorkflowEngine.class)).hasSize(1);
            assertThat(ctx.getBean(WorkflowEngine.class)).isInstanceOf(InProcessWorkflowEngine.class);
            assertThat(ctx.getBeansOfType(ProcessEngine.class)).isEmpty();
            assertThat(ctx.getBeansOfType(DataSource.class))
                    .as("nothing scanned may define a DataSource that could shadow the app's")
                    .isEmpty();
        });
    }

    @Test
    void noScannableConfigurationInTheAppTreeDefinesInfrastructureBeansOutsideSrcMain() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        scanner.addExcludeFilter(new AnnotationTypeFilter(TestConfiguration.class));
        List<String> offenders = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.samanvay")) {
            Class<?> type = ClassUtils.forName(bd.getBeanClassName(), getClass().getClassLoader());
            String location = type.getProtectionDomain().getCodeSource().getLocation().toString();
            if (!location.contains("/test-classes/")) {
                continue;
            }
            for (var m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Bean.class)
                        && (DataSource.class.isAssignableFrom(m.getReturnType())
                                || PlatformTransactionManager.class.isAssignableFrom(m.getReturnType()))) {
                    offenders.add(type.getName() + "#" + m.getName());
                }
            }
        }
        assertThat(offenders)
                .as("scanned test configurations that would replace the app's DataSource/transaction manager;"
                        + " make them @TestConfiguration")
                .isEmpty();
    }
}
