package io.github.laiwf94.payflow.sim;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Marks simulator beans that only exist when {@code payflow.simulator.enabled} is true. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ConditionalOnProperty(name = "payflow.simulator.enabled", matchIfMissing = true)
@interface EnabledSimulator {}
