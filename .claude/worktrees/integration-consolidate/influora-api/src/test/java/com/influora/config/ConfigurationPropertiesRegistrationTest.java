package com.influora.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.InfluoraApiApplication;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Guards against an entire class of boot failure that NO other test here can see.
 *
 * <p>A {@code @ConfigurationProperties} class is only turned into a bean if something registers it:
 * an {@link EnableConfigurationProperties} entry, a {@code @ConfigurationPropertiesScan}, a
 * stereotype like {@link Component}, or an explicit {@code @Bean}. If a live bean
 * constructor-injects one that was never registered, Spring cannot resolve it and the application
 * context FAILS TO START.
 *
 * <p>That is exactly what happened: {@code BrandSafetyAiProperties}, {@code TrendSparkAiProperties},
 * and {@code BrandSafetyServiceTokenProperties} were injected by {@code BrandSafetyAiClient},
 * {@code TrendSparkAiClient}, {@code BrandSafetyServiceTokenService} and {@code
 * SecretsStartupValidator} while being registered nowhere. The app could not boot.
 *
 * <p>It survived because of a verification blind spot, not bad luck. The only tests that build a
 * real context are the 4 {@code @SpringBootTest} classes, and every one of them errors out on
 * testcontainers/Docker discovery in this environment; the unit tests construct these beans by hand
 * with {@code new}/Mockito, so they can never observe the wiring. This test deliberately uses plain
 * classpath scanning and NO Spring context, so it runs green or red on a machine with no Docker at
 * all.
 */
class ConfigurationPropertiesRegistrationTest {

    private static final String BASE_PACKAGE = "com.influora";

    @Test
    @DisplayName("every @ConfigurationProperties class is actually registered as a bean")
    void everyConfigurationPropertiesClassIsRegistered() {
        Set<String> declared = declaredOnApplication();
        Set<String> unregistered = new TreeSet<>();

        for (Class<?> found : scanForConfigurationProperties()) {
            // Registered explicitly on the application class?
            if (declared.contains(found.getName())) {
                continue;
            }
            // Or self-registering via a stereotype. Must use Spring's merged-annotation
            // lookup, NOT Class#getAnnotation: @Service/@Repository/@Configuration are
            // META-annotated with @Component, and plain reflection does not traverse
            // meta-annotations, so getAnnotation(Component.class) returns null for an
            // @Service class and would report it as a false positive.
            if (AnnotatedElementUtils.hasAnnotation(found, Component.class)) {
                continue;
            }
            unregistered.add(found.getName());
        }

        assertThat(unregistered)
                .as(
                        "These @ConfigurationProperties classes are not registered anywhere, so Spring "
                            + "cannot create them. Any bean that constructor-injects one will fail the "
                            + "context at startup. Add them to @EnableConfigurationProperties on "
                            + "InfluoraApiApplication (or give them a stereotype).")
                .isEmpty();
    }

    /** Class names listed in {@code @EnableConfigurationProperties} on the application class. */
    private static Set<String> declaredOnApplication() {
        EnableConfigurationProperties annotation =
                InfluoraApiApplication.class.getAnnotation(EnableConfigurationProperties.class);
        assertThat(annotation)
                .as("InfluoraApiApplication must carry @EnableConfigurationProperties")
                .isNotNull();
        return Arrays.stream(annotation.value()).map(Class::getName).collect(Collectors.toSet());
    }

    /** Every {@code @ConfigurationProperties}-annotated class under com.influora. */
    private static Set<Class<?>> scanForConfigurationProperties() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        // Default impl rejects non-concrete types; we want every annotated class.
                        return true;
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));

        Set<Class<?>> found = new LinkedHashSet<>();
        scanner
                .findCandidateComponents(BASE_PACKAGE)
                .forEach(
                        definition -> {
                            try {
                                found.add(Class.forName(definition.getBeanClassName()));
                            } catch (ClassNotFoundException e) {
                                throw new IllegalStateException(
                                        "scanned but could not load " + definition.getBeanClassName(), e);
                            }
                        });

        assertThat(found)
                .as("sanity: the scanner must find the @ConfigurationProperties classes at all")
                .isNotEmpty();
        return found;
    }
}
