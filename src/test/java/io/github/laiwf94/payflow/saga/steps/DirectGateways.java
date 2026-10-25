package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.InventoryService;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.PaymentInfo;
import io.github.laiwf94.payflow.gateway.PaymentStatus;
import io.github.laiwf94.payflow.gateway.ShipmentInfo;
import io.github.laiwf94.payflow.gateway.ShippingService;
import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.ShippingAddress;
import io.github.laiwf94.payflow.sim.InventorySimulator;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator;
import io.github.laiwf94.payflow.sim.ShippingSimulator;
import io.github.laiwf94.payflow.sim.Simulated;
import io.github.laiwf94.payflow.sim.SimulatorException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The gateways wired straight to the simulators, with the same outcome rules as the HTTP adapters
 * (which {@code GatewayHttpTest} and {@code HttpDependencyTest} cover): a 503 is transient, a lost
 * answer is unknown, a 4xx is a rejection. Skipping HTTP lets the chaos test run thousands of
 * calls, and lost answers cost nothing instead of a read timeout each.
 */
final class DirectGateways {

  private DirectGateways() {}

  static <S, T> CallResult<T> call(Supplier<Simulated<S>> op, Function<S, CallResult<T>> map) {
    Simulated<S> s;
    try {
      s = op.get();
    } catch (SimulatorException e) {
      return new CallResult.Rejected<>(e.code(), e.getMessage());
    }
    return switch (s) {
      case Simulated.Answered<S> a -> map.apply(a.value());
      case Simulated.Unavailable<S> u -> new CallResult.Transient<>("503");
      case Simulated.Unanswered<S> u -> new CallResult.Unknown<>("no answer");
    };
  }

  static PaymentInfo info(PaymentProviderSimulator.Payment p) {
    return new PaymentInfo(
        p.id(),
        p.reference(),
        p.amount(),
        p.currency(),
        PaymentStatus.valueOf(p.status().name()),
        p.captured(),
        p.refunded(),
        p.declineCode());
  }

  static ShipmentInfo info(ShippingSimulator.Shipment s) {
    return new ShipmentInfo(s.id(), s.reference(), s.trackingNumber(), s.status().name());
  }

  static PaymentGateway payments(PaymentProviderSimulator sim) {
    return new PaymentGateway() {
      @Override
      public CallResult<PaymentInfo> authorize(
          String key, String reference, Money amount, String method) {
        return call(
            () -> sim.authorize(key, reference, amount.minorUnits(), amount.currencyCode(), method),
            p ->
                p.status() == PaymentProviderSimulator.Status.DECLINED
                    ? new CallResult.Rejected<>(p.declineCode(), "declined")
                    : new CallResult.Success<>(info(p)));
      }

      @Override
      public CallResult<PaymentInfo> capture(String key, String paymentId, long amount) {
        return call(
            () -> sim.capture(key, paymentId, amount), p -> new CallResult.Success<>(info(p)));
      }

      @Override
      public CallResult<PaymentInfo> voidAuthorization(String key, String paymentId) {
        return call(
            () -> sim.voidAuthorization(key, paymentId), p -> new CallResult.Success<>(info(p)));
      }

      @Override
      public CallResult<PaymentInfo> refund(String key, String paymentId, long amount) {
        return call(
            () -> sim.refund(key, paymentId, amount), p -> new CallResult.Success<>(info(p)));
      }

      @Override
      public CallResult<List<PaymentInfo>> findByReference(String reference) {
        return call(
            () -> sim.findByReference(reference),
            l -> new CallResult.Success<>(l.stream().map(DirectGateways::info).toList()));
      }
    };
  }

  static InventoryService inventory(InventorySimulator sim) {
    return new InventoryService() {
      @Override
      public CallResult<String> reserve(String reservationId, List<LineItem> items) {
        return call(
            () ->
                sim.reserve(
                    reservationId,
                    items.stream()
                        .map(i -> new InventorySimulator.Line(i.sku(), i.quantity()))
                        .toList()),
            r -> new CallResult.Success<>(r.id()));
      }

      @Override
      public CallResult<Boolean> release(String reservationId) {
        return call(() -> sim.release(reservationId), r -> new CallResult.Success<>(Boolean.TRUE));
      }
    };
  }

  static ShippingService shipping(ShippingSimulator sim) {
    return new ShippingService() {
      @Override
      public CallResult<ShipmentInfo> create(String reference, ShippingAddress address) {
        return call(
            () -> sim.create(reference, address.postalCode(), address.country()),
            s -> new CallResult.Success<>(info(s)));
      }

      @Override
      public CallResult<ShipmentInfo> cancel(String shipmentId) {
        return call(() -> sim.cancel(shipmentId), s -> new CallResult.Success<>(info(s)));
      }

      @Override
      public CallResult<Optional<ShipmentInfo>> findByReference(String reference) {
        return call(
            () -> sim.findByReference(reference),
            s -> new CallResult.Success<>(s.map(DirectGateways::info)));
      }
    };
  }
}
