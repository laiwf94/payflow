package io.github.laiwf94.payflow.refund;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Refunds in Postgres. Like an order's saga, every write by the refund worker is a compare-and-set
 * on {@code (id, lease_token)} with the refund still {@code PENDING}, so a worker whose lease was
 * taken over can't settle a refund twice. The money rules (refunds within the capture and within
 * the goods bought) are the database's: see {@code V9__refunds.sql}, and for each tender's share of
 * a refund {@code V33__refund_allocations.sql}. Every refund read here comes with its shares.
 */
@Repository
public class RefundRepository {

  private static final TypeReference<List<RefundLine>> LINES = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public RefundRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void insert(Refund r) {
    jdbc.sql(
            """
            INSERT INTO refunds (id, order_id, amount_minor, lines, state, attempt,
                next_attempt_at, created_at, updated_at)
            VALUES (:id, :order, :amount, CAST(:lines AS jsonb), :state, :attempt,
                :nextAttemptAt, :createdAt, :updatedAt)
            """)
        .param("id", r.id())
        .param("order", r.orderId())
        .param("amount", r.amountMinor())
        .param("lines", r.lines().isEmpty() ? null : write(r.lines()))
        .param("state", r.state().name())
        .param("attempt", r.attempt())
        .param("nextAttemptAt", ts(r.nextAttemptAt()))
        .param("createdAt", ts(r.createdAt()))
        .param("updatedAt", ts(r.updatedAt()))
        .update();
  }

  /**
   * Stores the shares of {@code refund} split by {@code policy}, made in the order given, and
   * records the policy on the refund. In the transaction that creates the refund, under its order's
   * lock.
   */
  public Refund insertAllocations(
      Refund refund, AllocationPolicy policy, List<AllocationPolicy.Share> shares, Instant now) {
    int n =
        jdbc.sql(
                """
                UPDATE refunds SET allocation_policy = :policy
                WHERE id = :id AND state = 'PENDING' AND allocation_policy IS NULL
                """)
            .param("policy", policy.code())
            .param("id", refund.id())
            .update();
    if (n != 1) {
      throw new IllegalStateException("refund " + refund.id() + " is already allocated");
    }
    writeShares(refund.id(), shares, now);
    return findById(refund.id()).orElseThrow();
  }

  /**
   * Gives a leased refund that has no shares yet (one an older binary created) its shares. Empty,
   * writing nothing, when the lease was lost or the refund already has them. The caller holds the
   * order's lock.
   */
  public Optional<Refund> allocateLeased(
      UUID id,
      UUID leaseToken,
      AllocationPolicy policy,
      List<AllocationPolicy.Share> shares,
      Instant now) {
    int n =
        jdbc.sql(
                """
                UPDATE refunds SET allocation_policy = :policy
                WHERE id = :id AND lease_token = :token AND state = 'PENDING'
                  AND NOT EXISTS (SELECT 1 FROM refund_allocations WHERE refund_id = :id)
                """)
            .param("policy", policy.code())
            .param("id", id)
            .param("token", leaseToken)
            .update();
    if (n != 1) {
      return Optional.empty();
    }
    writeShares(id, shares, now);
    return findById(id);
  }

  private void writeShares(UUID refundId, List<AllocationPolicy.Share> shares, Instant now) {
    if (shares.isEmpty()) {
      throw new IllegalArgumentException("a refund needs at least one share");
    }
    for (int i = 0; i < shares.size(); i++) {
      AllocationPolicy.Share sh = shares.get(i);
      jdbc.sql(
              """
              INSERT INTO refund_allocations (refund_id, payment_id, seq, amount_minor, state,
                  created_at, updated_at)
              VALUES (:refund, :payment, :seq, :amount, 'PENDING', :now, :now)
              """)
          .param("refund", refundId)
          .param("payment", sh.paymentId())
          .param("seq", i + 1)
          .param("amount", sh.amountMinor())
          .param("now", ts(now))
          .update();
    }
  }

  /**
   * Settles one pending share of a leased refund: {@code providerRefundId} when the tender's
   * provider returned it, otherwise refused with {@code code}. The refund stays pending and keeps
   * its lease. False, writing nothing, when the lease was lost or the share is already settled.
   */
  public boolean settleShare(
      UUID refundId,
      UUID leaseToken,
      UUID paymentId,
      String providerRefundId,
      String code,
      String message,
      Instant now) {
    return jdbc.sql(
                """
                UPDATE refund_allocations a SET
                    state = CASE WHEN CAST(:provider AS text) IS NULL THEN 'FAILED'
                                 ELSE 'SUCCEEDED' END,
                    provider_refund_id = :provider, failure_code = :code,
                    failure_message = :message, updated_at = :now
                FROM refunds r
                WHERE a.refund_id = r.id AND r.id = :refund AND r.lease_token = :token
                  AND r.state = 'PENDING' AND a.payment_id = :payment AND a.state = 'PENDING'
                """)
            .param("provider", providerRefundId)
            .param("code", code)
            .param("message", message)
            .param("now", ts(now))
            .param("refund", refundId)
            .param("token", leaseToken)
            .param("payment", paymentId)
            .update()
        == 1;
  }

  /**
   * What the shares of an order's refunds that haven't failed take from each of its tenders, by
   * payment id: the money each tender has returned or is returning. Exact only while the order is
   * locked, like {@link #activeTotal}.
   */
  public Map<UUID, Long> activeByTender(UUID orderId) {
    Map<UUID, Long> out = new HashMap<>();
    jdbc.sql(
            """
            SELECT a.payment_id, sum(a.amount_minor) AS taken
            FROM refund_allocations a JOIN payments p ON p.id = a.payment_id
            WHERE p.order_id = :order AND a.state <> 'FAILED'
            GROUP BY a.payment_id
            """)
        .param("order", orderId)
        .query(
            (RowCallbackHandler)
                rs -> out.put(rs.getObject("payment_id", UUID.class), rs.getLong("taken")));
    return out;
  }

  public Optional<Refund> findById(UUID id) {
    return jdbc.sql("SELECT * FROM refunds WHERE id = :id")
        .param("id", id)
        .query((rs, n) -> map(rs))
        .optional()
        .map(r -> attach(List.of(r)).getFirst());
  }

  /**
   * {@code rows} with their shares, read in one query: each share with its tender's position,
   * method and provider payment id.
   */
  private List<Refund> attach(List<Refund> rows) {
    if (rows.isEmpty()) {
      return rows;
    }
    Map<UUID, List<RefundAllocation>> byRefund = new HashMap<>();
    jdbc.sql(
            """
            SELECT a.*, p.position, p.method, p.provider, p.provider_payment_id
            FROM refund_allocations a JOIN payments p ON p.id = a.payment_id
            WHERE a.refund_id IN (:ids)
            ORDER BY a.refund_id, a.seq
            """)
        .param("ids", rows.stream().map(Refund::id).toList())
        .query(
            (RowCallbackHandler)
                rs ->
                    byRefund
                        .computeIfAbsent(
                            rs.getObject("refund_id", UUID.class), k -> new ArrayList<>())
                        .add(mapShare(rs)));
    return rows.stream()
        .map(r -> r.withAllocations(r.allocationPolicy(), byRefund.getOrDefault(r.id(), List.of())))
        .toList();
  }

  private static RefundAllocation mapShare(ResultSet rs) throws SQLException {
    return new RefundAllocation(
        rs.getObject("refund_id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getInt("seq"),
        rs.getInt("position"),
        rs.getString("method"),
        rs.getString("provider"),
        rs.getString("provider_payment_id"),
        rs.getLong("amount_minor"),
        RefundState.valueOf(rs.getString("state")),
        rs.getString("provider_refund_id"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  /** An order's refunds, oldest first. */
  public List<Refund> listForOrder(UUID orderId) {
    return attach(
        jdbc.sql("SELECT * FROM refunds WHERE order_id = :order ORDER BY created_at, id")
            .param("order", orderId)
            .query((rs, n) -> map(rs))
            .list());
  }

  /**
   * The {@link RefundSummary} of each of {@code orderIds}; orders without refunds are left out. Two
   * statements read it, so a caller that needs it to agree with the orders reads both in one
   * snapshot or under the orders' locks. Each order's newest refunds come from the {@code
   * refunds_order} index, however many it has.
   */
  public Map<UUID, RefundSummary> summaries(Collection<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<Refund>> recent = new HashMap<>();
    List<Refund> read = new ArrayList<>();
    jdbc.sql(
            """
            SELECT r.* FROM orders o
            CROSS JOIN LATERAL (
                SELECT * FROM refunds WHERE order_id = o.id
                ORDER BY created_at DESC, id DESC
                LIMIT :limit) r
            WHERE o.id IN (:ids)
            ORDER BY r.order_id, r.created_at DESC, r.id DESC
            """)
        .param("ids", orderIds)
        .param("limit", RefundSummary.RECENT + 1)
        .query(
            (RowCallbackHandler)
                rs -> {
                  read.add(map(rs));
                });
    for (Refund r : attach(read)) {
      recent.computeIfAbsent(r.orderId(), k -> new ArrayList<>()).add(r);
    }
    Map<UUID, Long> pending = new HashMap<>();
    jdbc.sql(
            """
            SELECT order_id, sum(amount_minor) AS pending FROM refunds
            WHERE order_id IN (:ids) AND state = 'PENDING'
            GROUP BY order_id
            """)
        .param("ids", orderIds)
        .query(
            (RowCallbackHandler)
                rs -> pending.put(rs.getObject("order_id", UUID.class), rs.getLong("pending")));
    Map<UUID, RefundSummary> out = new HashMap<>();
    recent.forEach(
        (order, rows) -> {
          boolean more = rows.size() > RefundSummary.RECENT;
          out.put(
              order,
              new RefundSummary(
                  more ? rows.subList(0, RefundSummary.RECENT) : rows,
                  more,
                  pending.getOrDefault(order, 0L)));
        });
    return out;
  }

  /**
   * What an order's refunds that haven't failed add up to: the money already returned or promised
   * back, counting only what came back of a partial failure. Exact only while the order is locked
   * (see {@code OrderRepository#lockForRefund}).
   */
  public long activeTotal(UUID orderId) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(CASE WHEN state = 'PARTIALLY_FAILED' THEN returned_minor
                                     ELSE amount_minor END), 0)
            FROM refunds
            WHERE order_id = :order AND state <> 'FAILED'
            """)
        .param("order", orderId)
        .query(Long.class)
        .single();
  }

  /**
   * How many units of each sku an order's refunds that haven't failed give back. Exact only while
   * the order is locked, like {@link #activeTotal}.
   */
  public Map<String, Long> activeLineQuantities(UUID orderId) {
    Map<String, Long> out = new HashMap<>();
    jdbc.sql(
            """
            SELECT l ->> 'sku' AS sku, sum((l ->> 'quantity')::bigint) AS quantity
            FROM refunds, jsonb_array_elements(lines) AS l
            WHERE order_id = :order AND state <> 'FAILED' AND lines IS NOT NULL
            GROUP BY 1
            """)
        .param("order", orderId)
        .query((RowCallbackHandler) rs -> out.put(rs.getString("sku"), rs.getLong("quantity")));
    return out;
  }

  /**
   * Leases up to {@code limit} due refunds. {@code SKIP LOCKED} lets every instance poll without
   * blocking on the others; the lease, not the row lock, protects a refund while the provider is
   * asked, because no transaction stays open across that call.
   */
  public List<RefundClaim> claimDue(Instant now, Duration lease, int limit) {
    List<RefundClaim> claims =
        jdbc.sql(
                """
            WITH due AS (
                SELECT id, lease_token AS expired_token
                FROM refunds
                WHERE next_attempt_at <= :now
                  AND (lease_until IS NULL OR lease_until < :now)
                ORDER BY next_attempt_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE refunds r
            SET lease_token = gen_random_uuid(), lease_until = :leaseUntil
            FROM due
            WHERE r.id = due.id
            RETURNING r.*, due.expired_token IS NOT NULL AS recovered
            """)
            .param("now", ts(now))
            .param("leaseUntil", ts(now.plus(lease)))
            .param("limit", limit)
            .query(
                (rs, n) ->
                    new RefundClaim(
                        map(rs),
                        rs.getObject("lease_token", UUID.class),
                        rs.getBoolean("recovered")))
            .list();
    Map<UUID, Refund> withShares = new HashMap<>();
    attach(claims.stream().map(RefundClaim::refund).toList())
        .forEach(r -> withShares.put(r.id(), r));
    return claims.stream()
        .map(c -> new RefundClaim(withShares.get(c.refund().id()), c.leaseToken(), c.recovered()))
        .toList();
  }

  /** Settles a leased refund as made in full. Empty when the lease was lost. */
  public Optional<Refund> succeed(
      UUID id, UUID leaseToken, String providerRefundId, long amount, Instant now) {
    return settle(id, leaseToken, RefundState.SUCCEEDED, providerRefundId, amount, null, null, now);
  }

  /** Settles a leased refund as refused, nothing returned. Empty when the lease was lost. */
  public Optional<Refund> fail(UUID id, UUID leaseToken, String code, String message, Instant now) {
    return settle(id, leaseToken, RefundState.FAILED, null, 0, code, message, now);
  }

  /**
   * Settles a leased refund as partly made: {@code returned} went back, as {@code providerRefundId}
   * among others, and the rest was refused. Empty when the lease was lost.
   */
  public Optional<Refund> partiallyFail(
      UUID id,
      UUID leaseToken,
      String providerRefundId,
      long returned,
      String code,
      String message,
      Instant now) {
    return settle(
        id,
        leaseToken,
        RefundState.PARTIALLY_FAILED,
        providerRefundId,
        returned,
        code,
        message,
        now);
  }

  /**
   * The settling write. It marks its transaction as this binary's ({@code payflow.refund_writer}),
   * so the trigger that settles an older binary's shares for it stays out of the way: this binary
   * settles each share itself before the refund.
   */
  private Optional<Refund> settle(
      UUID id,
      UUID leaseToken,
      RefundState state,
      String providerRefundId,
      long returned,
      String failureCode,
      String failureMessage,
      Instant now) {
    return jdbc.sql(
            """
            UPDATE refunds SET state = :state,
                provider_refund_id = COALESCE(CAST(:provider AS text), provider_refund_id),
                returned_minor = :returned,
                failure_code = :failureCode, failure_message = :failureMessage,
                next_attempt_at = NULL, lease_token = NULL, lease_until = NULL, updated_at = :now
            WHERE id = :id AND lease_token = :token AND state = 'PENDING'
              AND set_config('payflow.refund_writer', 'allocations', true) = 'allocations'
            RETURNING *
            """)
        .param("state", state.name())
        .param("returned", returned)
        .param("provider", providerRefundId)
        .param("failureCode", failureCode)
        .param("failureMessage", failureMessage)
        .param("now", ts(now))
        .param("id", id)
        .param("token", leaseToken)
        .query((rs, n) -> map(rs))
        .optional()
        .map(r -> attach(List.of(r)).getFirst());
  }

  /** Schedules the next try and gives the lease back. False when the lease was lost. */
  public boolean retry(UUID id, UUID leaseToken, int attempt, Instant nextAttemptAt, Instant now) {
    return jdbc.sql(
                """
                UPDATE refunds SET attempt = :attempt, next_attempt_at = :next,
                    lease_token = NULL, lease_until = NULL, updated_at = :now
                WHERE id = :id AND lease_token = :token AND state = 'PENDING'
                """)
            .param("attempt", attempt)
            .param("next", ts(nextAttemptAt))
            .param("now", ts(now))
            .param("id", id)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  /** Gives the lease back without changing anything else. */
  public boolean release(UUID id, UUID leaseToken) {
    return jdbc.sql(
                """
                UPDATE refunds SET lease_token = NULL, lease_until = NULL
                WHERE id = :id AND lease_token = :token
                """)
            .param("id", id)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  /**
   * Hands back a lease whose provider call may still be running, as if this worker had died: the
   * token is replaced, fencing out a late write, and the lease is expired for every clock, so the
   * next poll claims the refund at once and is told it was {@code recovered}.
   */
  public boolean abandon(UUID id, UUID leaseToken) {
    return jdbc.sql(
                """
                UPDATE refunds SET lease_token = gen_random_uuid(), lease_until = '-infinity'
                WHERE id = :id AND lease_token = :token
                """)
            .param("id", id)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  private Refund map(ResultSet rs) throws SQLException {
    String lines = rs.getString("lines");
    RefundState state = RefundState.valueOf(rs.getString("state"));
    long amount = rs.getLong("amount_minor");
    String policy = rs.getString("allocation_policy");
    return new Refund(
        rs.getObject("id", UUID.class),
        rs.getObject("order_id", UUID.class),
        amount,
        lines == null ? List.of() : read(lines),
        state,
        rs.getString("provider_refund_id"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        rs.getInt("attempt"),
        instant(rs, "next_attempt_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        Refund.returnedFor(state, amount, rs.getObject("returned_minor", Long.class)),
        policy == null ? null : AllocationPolicy.fromCode(policy),
        List.of());
  }

  private static OffsetDateTime ts(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private String write(List<RefundLine> lines) {
    try {
      return json.writeValueAsString(lines);
    } catch (JacksonException e) {
      throw new IllegalStateException("can't serialise refund lines", e);
    }
  }

  private List<RefundLine> read(String value) {
    try {
      return json.readValue(value, LINES);
    } catch (JacksonException e) {
      throw new IllegalStateException("stored refund lines are not valid JSON", e);
    }
  }
}
