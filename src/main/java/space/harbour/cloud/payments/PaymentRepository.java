package space.harbour.cloud.payments;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sharded store for payments backed by N independent Postgres databases.
 *
 * <p>Routing is by {@code payment_id}: a payment lives on exactly one shard,
 * {@code hash(paymentId) % shardCount}. That makes the two single-row paths -
 * insert and lookup-by-id - touch exactly one shard. Lookups that are not by id
 * (all payments for a store, or all payments in a batch) cannot know the shard
 * up front, so they scatter-gather across every shard and merge the results.
 */
@Repository
public class PaymentRepository {

	private static final String INSERT = """
			INSERT INTO payments
			    (payment_id, batch_id, store_id, coffee_type, price, currency,
			     loyalty_card_id, idempotency_key, status, remote_ref, registered_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String SELECT_BY_ID =
			"SELECT * FROM payments WHERE payment_id = ?";

	private static final String SELECT_BY_STORE =
			"SELECT * FROM payments WHERE store_id = ?";

	private static final String SELECT_BY_BATCH =
			"SELECT * FROM payments WHERE batch_id = ?";

	private static final String SELECT_PENDING_IN_BATCHES =
			"SELECT * FROM payments WHERE status = 'PENDING' AND batch_id IS NOT NULL";

	private static final RowMapper<Payment> ROW_MAPPER = (rs, rowNum) -> new Payment(
			rs.getString("payment_id"),
			rs.getString("batch_id"),
			rs.getString("store_id"),
			CoffeeType.valueOf(rs.getString("coffee_type")),
			rs.getBigDecimal("price"),
			rs.getString("currency"),
			rs.getString("loyalty_card_id"),
			rs.getString("idempotency_key"),
			PaymentStatus.valueOf(rs.getString("status")),
			rs.getString("remote_ref"),
			rs.getTimestamp("registered_at").toInstant());

	private final ShardSet shards;

	public PaymentRepository(ShardSet shards) {
		this.shards = shards;
	}

	// --- Synchronous single-payment path -----------------------------------

	/**
	 * Inserts the payment on its owning shard, unless a payment with the same id
	 * already exists there (the duplicate-key path makes registration idempotent:
	 * the same store + idempotency key always yields the same id, hence the same
	 * shard, hence a primary-key clash on replay).
	 *
	 * @return the stored payment plus whether this call created it (vs. replayed
	 *         an earlier identical request).
	 */
	public SaveResult saveIfAbsent(Payment payment) {
		JdbcTemplate shard = shards.forKey(payment.paymentId());
		try {
			insert(shard, payment);
			return new SaveResult(payment, true);
		} catch (DuplicateKeyException replay) {
			Payment existing = findById(payment.paymentId())
					.orElseThrow(() -> replay); // lost the row between insert and read - re-surface the clash
			return new SaveResult(existing, false);
		}
	}

	/** Outcome of {@link #saveIfAbsent}: the persisted row and whether it was new. */
	public record SaveResult(Payment payment, boolean created) {
	}

	// --- Async batch path ---------------------------------------------------

	/** Bulk-inserts a batch's payments, each routed to its own shard by id. */
	public void saveBatchPayments(List<Payment> payments) {
		for (Payment payment : payments) {
			insert(shards.forKey(payment.paymentId()), payment);
		}
	}

	/** Pending, batch-owned payments awaiting processing, gathered from all shards. */
	public List<Payment> findPendingBatchPayments() {
		List<Payment> pending = new ArrayList<>();
		for (JdbcTemplate shard : shards.all()) {
			pending.addAll(shard.query(SELECT_PENDING_IN_BATCHES, ROW_MAPPER));
		}
		return pending;
	}

	/**
	 * Atomically claims a pending payment for processing
	 * ({@code PENDING -> PROCESSING}). Only the worker that wins this update
	 * proceeds to call the remote system, so a payment is never processed twice.
	 *
	 * @return true if this call claimed the payment.
	 */
	public boolean claim(String paymentId) {
		int updated = shards.forKey(paymentId).update(
				"UPDATE payments SET status = 'PROCESSING' WHERE payment_id = ? AND status = 'PENDING'",
				paymentId);
		return updated == 1;
	}

	/** Marks a claimed payment done and records its remote-system reference. */
	public void markDone(String paymentId, String remoteReference) {
		shards.forKey(paymentId).update(
				"UPDATE payments SET status = 'DONE', remote_ref = ? WHERE payment_id = ?",
				remoteReference, paymentId);
	}

	/** Releases a claimed-but-failed payment back to PENDING so it is retried. */
	public void releaseClaim(String paymentId) {
		shards.forKey(paymentId).update(
				"UPDATE payments SET status = 'PENDING' WHERE payment_id = ? AND status = 'PROCESSING'",
				paymentId);
	}

	/** How many of a batch's payments are DONE (scatter-gather across shards). */
	public long countDoneInBatch(String batchId) {
		long done = 0;
		for (JdbcTemplate shard : shards.all()) {
			Long c = shard.queryForObject(
					"SELECT COUNT(*) FROM payments WHERE batch_id = ? AND status = 'DONE'",
					Long.class, batchId);
			done += (c == null ? 0 : c);
		}
		return done;
	}

	// --- Lookups ------------------------------------------------------------

	/** Single-shard lookup: the id alone tells us which shard to ask. */
	public Optional<Payment> findById(String paymentId) {
		JdbcTemplate shard = shards.forKey(paymentId);
		return shard.query(SELECT_BY_ID, ROW_MAPPER, paymentId).stream().findFirst();
	}

	/** Scatter-gather: a store's payments are spread across every shard. */
	public List<Payment> findByStoreId(String storeId) {
		return scatter(SELECT_BY_STORE, storeId);
	}

	/** Scatter-gather: a batch's payments are spread across every shard. */
	public List<Payment> findByBatchId(String batchId) {
		return scatter(SELECT_BY_BATCH, batchId);
	}

	private List<Payment> scatter(String sql, String arg) {
		List<Payment> merged = new ArrayList<>();
		for (JdbcTemplate shard : shards.all()) {
			merged.addAll(shard.query(sql, ROW_MAPPER, arg));
		}
		return merged;
	}

	/**
	 * Row count per shard, keyed by shard index. Used to demonstrate that the
	 * hash function spreads payments roughly uniformly across the shards.
	 */
	public Map<Integer, Long> countByShard() {
		Map<Integer, Long> counts = new LinkedHashMap<>();
		List<JdbcTemplate> all = shards.all();
		for (int i = 0; i < all.size(); i++) {
			Long count = all.get(i).queryForObject("SELECT COUNT(*) FROM payments", Long.class);
			counts.put(i, count == null ? 0L : count);
		}
		return counts;
	}

	// --- internals ----------------------------------------------------------

	private static void insert(JdbcTemplate shard, Payment p) {
		shard.update(INSERT,
				p.paymentId(),
				p.batchId(),
				p.storeId(),
				p.coffeeType().name(),
				p.price(),
				p.currency(),
				p.loyaltyCardId(),
				p.idempotencyKey(),
				p.status().name(),
				p.remoteReference(),
				Timestamp.from(p.registeredAt()));
	}
}
