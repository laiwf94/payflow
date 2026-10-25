package io.github.laiwf94.payflow.gateway;

/** Status of one payment at the provider. */
public enum PaymentStatus {
  AUTHORIZED,
  DECLINED,
  CAPTURED,
  VOIDED
}
