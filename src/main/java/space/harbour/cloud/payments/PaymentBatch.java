package space.harbour.cloud.payments;

import java.time.Instant;

/**
 * A bulk payment request the client submitted - the unit the client tracks by id.
 *
 * <p>Stored in the {@code payment_batches} table, sharded by {@code batchId}.
 * Its individual payments live in the {@code payments} table (sharded by payment
 * id, so spread across shards) and reference this batch via their batch id.
 */
public record PaymentBatch(
		String batchId,
		String storeId,
		BatchStatus status,
		int totalPayments,
		Instant createdAt,
		Instant updatedAt
) {
}
