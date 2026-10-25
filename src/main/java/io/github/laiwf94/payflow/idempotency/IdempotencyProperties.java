package io.github.laiwf94.payflow.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Idempotency key settings ({@code payflow.idempotency.*}).
 *
 * @param lockTimeout how long a request may hold its key before another request with the same key
 *     may take it over; must exceed the slowest request
 * @param retention how long keys and their responses are kept
 */
@ConfigurationProperties("payflow.idempotency")
public record IdempotencyProperties(
    @DefaultValue("60s") Duration lockTimeout, @DefaultValue("24h") Duration retention) {}
