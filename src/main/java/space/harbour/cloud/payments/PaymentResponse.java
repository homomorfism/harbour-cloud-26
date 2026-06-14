package space.harbour.cloud.payments;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the client gets back after registering a payment.
 */
public record PaymentResponse(
		String paymentId,
		String batchId,
		String storeId,
		CoffeeType coffeeType,
		BigDecimal price,
		String currency,
		String loyaltyCardId,
		PaymentStatus status,
		String remoteReference,
		Instant registeredAt
) {
	static PaymentResponse from(Payment payment) {
		return new PaymentResponse(
				payment.paymentId(),
				payment.batchId(),
				payment.storeId(),
				payment.coffeeType(),
				payment.price(),
				payment.currency(),
				payment.loyaltyCardId(),
				payment.status(),
				payment.remoteReference(),
				payment.registeredAt()
		);
	}
}
