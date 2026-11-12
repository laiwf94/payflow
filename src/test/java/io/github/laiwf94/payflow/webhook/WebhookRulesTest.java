package io.github.laiwf94.payflow.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The event filter and URL rules an endpoint is registered under. */
class WebhookRulesTest {

  @Test
  void everyEventTheOutboxWritesCanBeSubscribedTo() {
    assertThat(WebhookEvents.TYPES)
        .startsWith("order.created", "order.cancel_requested")
        .endsWith("refund.created", "refund.succeeded", "refund.failed")
        .hasSize(2 + OrderState.values().length - 1 + 3)
        .doesNotContain("order.pending", "*");
    for (OrderState s : OrderState.values()) {
      if (s != OrderState.PENDING) {
        assertThat(WebhookEvents.TYPES).contains(OrderTransitions.eventType(s));
      }
    }
  }

  @Test
  void filtersAreNormalizedAndChecked() {
    assertThat(WebhookEvents.normalize(List.of("*"))).containsExactly("*");
    assertThat(
            WebhookEvents.normalize(
                List.of("refund.failed", "order.completed", "refund.failed", "order.completed")))
        .containsExactly("refund.failed", "order.completed");
    assertThat(WebhookEvents.normalize(new ArrayList<>(WebhookEvents.TYPES)))
        .containsExactlyElementsOf(WebhookEvents.TYPES);

    assertThatThrownBy(() -> WebhookEvents.normalize(List.of()))
        .hasMessage("enabled_events: must name at least one event type");
    assertThatThrownBy(() -> WebhookEvents.normalize(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WebhookEvents.normalize(List.of("order.shipped")))
        .hasMessageStartingWith("enabled_events: unknown event type \"order.shipped\"; use \"*\"");
    assertThatThrownBy(() -> WebhookEvents.normalize(Arrays.asList("order.created", null)))
        .hasMessageStartingWith("enabled_events: unknown event type null");
    assertThatThrownBy(() -> WebhookEvents.normalize(List.of("ORDER.CREATED")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WebhookEvents.normalize(List.of("x".repeat(500) + "\n")))
        .hasMessageStartingWith("enabled_events: unknown event type \"" + "x".repeat(64) + "...\"");
    assertThatThrownBy(() -> WebhookEvents.normalize(List.of("*", "order.created")))
        .hasMessage("enabled_events: \"*\" already means every type; give it alone");
    assertThatThrownBy(() -> WebhookEvents.normalize(List.of("*", "*", "order.created")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFilterMatchesItsTypesOrEverythingForTheWildcard() {
    assertThat(WebhookEvents.matches(List.of("*"), "refund.created")).isTrue();
    assertThat(WebhookEvents.matches(List.of("order.completed"), "order.completed")).isTrue();
    assertThat(WebhookEvents.matches(List.of("order.completed"), "order.created")).isFalse();
  }

  @Test
  void urlsAreNormalized() {
    assertThat(WebhookUrls.normalize("https://hooks.example.com"))
        .isEqualTo("https://hooks.example.com");
    assertThat(WebhookUrls.normalize("HTTPS://Hooks.Example.COM/Path/To?Key=A%20B&x=1"))
        .as("scheme and host lowercased; path and query kept as given")
        .isEqualTo("https://hooks.example.com/Path/To?Key=A%20B&x=1");
    assertThat(WebhookUrls.normalize("http://hooks.example.com:8443/in"))
        .isEqualTo("http://hooks.example.com:8443/in");
    assertThat(WebhookUrls.normalize("https://[2001:DB8::1]:9000/x"))
        .isEqualTo("https://[2001:db8::1]:9000/x");
    String longest = "https://h.example/" + "a".repeat(WebhookUrls.MAX_LENGTH - 18);
    assertThat(WebhookUrls.normalize(longest)).hasSize(WebhookUrls.MAX_LENGTH);
  }

  @Test
  void urlsWithoutTheRightShapeAreRefused() {
    for (String bad :
        List.of(
            "",
            "hooks.example.com/in",
            "/relative",
            "ftp://hooks.example.com",
            "mailto:ops@example.com",
            "javascript:alert(1)",
            "https:opaque",
            "https://",
            "https:///path",
            "https://user:pass@hooks.example.com",
            "https://user@hooks.example.com",
            "https://hooks.example.com/in#frag",
            "https://hooks.example.com:0/",
            "https://hooks.example.com:99999/",
            "https://hooks.example.com/a b",
            "https://hooks.example.com/é",
            "https://hooks.example.com/\n",
            "https://hooks.example.com/%zz",
            "https://h.example/" + "a".repeat(WebhookUrls.MAX_LENGTH - 17))) {
      assertThatThrownBy(() -> WebhookUrls.normalize(bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("url: ");
    }
    assertThatThrownBy(() -> WebhookUrls.normalize(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
