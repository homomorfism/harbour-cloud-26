package space.harbour.cloud.payments;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A coffee payment that has been accepted and recorded by the system.
 *
 * <p>In the async flow a payment belongs to a {@code batchId} and moves through
 * {@link PaymentStatus} as a worker creates its entry in the remote system,
 * recording the {@code remoteReference} it gets back. Payments registered via
 * the synchronous endpoint have a {@code null} batch id and are stored already
 * {@link PaymentStatus#DONE}.
 */
public record Payment(
		String paymentId,
		String batchId,
		String storeId,
		CoffeeType coffeeType,
		BigDecimal price,
		String currency,
		String loyaltyCardId,
		String idempotencyKey,
		PaymentStatus status,
		String remoteReference,
		Instant registeredAt
) {
}
