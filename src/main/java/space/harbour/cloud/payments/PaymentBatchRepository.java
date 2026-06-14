package space.harbour.cloud.payments;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sharded store for batches (bulk requests), routed by {@code batchId}.
 *
 * <p>A batch and its lookup-by-id therefore touch a single shard. Finding all
 * not-yet-finished batches (for the worker to reconcile) is a scatter-gather.
 */
@Repository
public class PaymentBatchRepository {

	private static final String INSERT = """
			INSERT INTO payment_batches
			    (batch_id, store_id, status, total_payments, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?)
			""";

	private static final RowMapper<PaymentBatch> ROW_MAPPER = (rs, rowNum) -> new PaymentBatch(
			rs.getString("batch_id"),
			rs.getString("store_id"),
			BatchStatus.valueOf(rs.getString("status")),
			rs.getInt("total_payments"),
			rs.getTimestamp("created_at").toInstant(),
			rs.getTimestamp("updated_at").toInstant());

	private final ShardSet shards;

	public PaymentBatchRepository(ShardSet shards) {
		this.shards = shards;
	}

	public void save(PaymentBatch batch) {
		shards.forKey(batch.batchId()).update(INSERT,
				batch.batchId(),
				batch.storeId(),
				batch.status().name(),
				batch.totalPayments(),
				Timestamp.from(batch.createdAt()),
				Timestamp.from(batch.updatedAt()));
	}

	/** Single-shard lookup by batch id. */
	public Optional<PaymentBatch> findById(String batchId) {
		return shards.forKey(batchId)
				.query("SELECT * FROM payment_batches WHERE batch_id = ?", ROW_MAPPER, batchId)
				.stream().findFirst();
	}

	/** Batches not yet DONE, gathered from every shard - the worker's work list. */
	public List<PaymentBatch> findUnfinished() {
		List<PaymentBatch> open = new ArrayList<>();
		for (JdbcTemplate shard : shards.all()) {
			open.addAll(shard.query(
					"SELECT * FROM payment_batches WHERE status <> 'DONE'", ROW_MAPPER));
		}
		return open;
	}

	public void updateStatus(String batchId, BatchStatus status, Instant updatedAt) {
		shards.forKey(batchId).update(
				"UPDATE payment_batches SET status = ?, updated_at = ? WHERE batch_id = ?",
				status.name(), Timestamp.from(updatedAt), batchId);
	}
}
