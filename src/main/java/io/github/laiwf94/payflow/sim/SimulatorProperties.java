package io.github.laiwf94.payflow.sim;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Simulator settings ({@code payflow.simulator.*}).
 *
 * @param enabled serve the simulators from this process
 * @param key bearer key callers must present; a random one is generated when blank
 * @param defaultStock units every SKU starts with
 * @param answerDelay how long an unanswered call holds the connection
 * @param seed seed for fault dice, for reproducible runs; random when null
 */
@ConfigurationProperties("payflow.simulator")
public record SimulatorProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("") String key,
    @DefaultValue("1000000") long defaultStock,
    @DefaultValue("5s") Duration answerDelay,
    Long seed) {}
