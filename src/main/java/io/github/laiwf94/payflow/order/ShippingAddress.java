package io.github.laiwf94.payflow.order;

/** Where the shipment goes. {@code country} is an ISO 3166-1 alpha-2 code. */
public record ShippingAddress(
    String name, String line1, String line2, String city, String postalCode, String country) {}
