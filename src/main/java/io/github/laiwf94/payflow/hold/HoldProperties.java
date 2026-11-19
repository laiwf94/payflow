package io.github.laiwf94.payflow.hold;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long an unpaid order may hold on, and how long an authorization lasts ({@code
 * payflow.holds.*}).
 *
 * @param reservationWindow how long an order may hold stock without its payment being captured,
 *     counted from the reservation; after that it is cancelled and the stock released
 * @param paymentWindow how long an order may go without its payment being captured, counted from
 *     its creation, whatever step it is stuck in; after that it is cancelled
 * @param authorizationLifetime how long the provider keeps an authorization before the hold on the
 *     card lapses, counted from when the authorization step started (never later than the
 *     authorization itself, so the expiry recorded is never later than the real one). Seven days is
 *     what the card networks give an online card payment. A manual-capture order still waiting for
 *     its capture then fails into the reversal.
 */
@ConfigurationProperties("payflow.holds")
public record HoldProperties(
    @DefaultValue("15m") Duration reservationWindow,
    @DefaultValue("1h") Duration paymentWindow,
    @DefaultValue("7d") Duration authorizationLifetime) {

  /** Longer than any authorization a card network keeps, so a hold this long is a mistake. */
  static final Duration MAX_WINDOW = Duration.ofDays(31);

  public HoldProperties {
    for (Duration d : new Duration[] {reservationWindow, paymentWindow, authorizationLifetime}) {
      if (d.compareTo(Duration.ofSeconds(1)) < 0 || d.compareTo(MAX_WINDOW) > 0) {
        throw new IllegalArgumentException(
            "hold windows and the authorization lifetime must be between 1 s and 31 days: " + d);
      }
    }
  }
}
