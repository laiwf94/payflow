package io.github.laiwf94.payflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.money.Money;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentProvidersTest {

  /** A provider no test calls; only its identity matters here. */
  private static final PaymentGateway NONE =
      new PaymentGateway() {
        @Override
        public CallResult<PaymentInfo> authorize(String k, String r, Money a, String m) {
          throw new UnsupportedOperationException();
        }

        @Override
        public CallResult<PaymentInfo> capture(String k, String id, long amount) {
          throw new UnsupportedOperationException();
        }

        @Override
        public CallResult<PaymentInfo> reauthorize(String k, String id) {
          throw new UnsupportedOperationException();
        }

        @Override
        public CallResult<PaymentInfo> voidAuthorization(String k, String id) {
          throw new UnsupportedOperationException();
        }

        @Override
        public CallResult<RefundInfo> refund(String k, String id, long amount, String r) {
          throw new UnsupportedOperationException();
        }

        @Override
        public CallResult<List<RefundInfo>> findRefunds(String id) {
          throw new UnsupportedOperationException();
        }

        @Override
        public CallResult<List<PaymentInfo>> findByReference(String r) {
          throw new UnsupportedOperationException();
        }
      };

  @Test
  void aTenderReachesTheProviderItNamesAndAnUnknownOneIsAnError() {
    PaymentGateway other = PaymentProviders.of(NONE).get("primary");
    PaymentProviders p = new PaymentProviders(Map.of("primary", NONE, "secondary", other));
    assertThat(p.get("primary")).isSameAs(NONE);
    assertThat(p.names()).containsExactlyInAnyOrder("primary", "secondary");
    assertThat(p.has("secondary")).isTrue();
    assertThat(PaymentProviders.of(NONE).has("secondary")).isFalse();
    assertThatThrownBy(() -> PaymentProviders.of(NONE).get("secondary"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("payment provider secondary is not configured");
  }

  @Test
  void thePrimaryProviderIsRequiredAndNamesMustBeStorable() {
    assertThatThrownBy(() -> new PaymentProviders(Map.of("secondary", NONE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("primary payment provider is required");
    assertThatThrownBy(() -> new PaymentProviders(Map.of("primary", NONE, "Second", NONE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider name Second");
  }
}
