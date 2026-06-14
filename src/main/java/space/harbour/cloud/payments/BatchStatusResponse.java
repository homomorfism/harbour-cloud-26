package space.harbour.cloud.payments;

import java.time.Instant;
import java.util.List;

/**
 * What the client gets when polling a batch by id: the overall status plus a
 * per-payment breakdown (including the remote-system reference once processed).
 */
public record BatchStatusResponse(
		String batchId,
		String storeId,
		BatchStatus status,
		int totalPayments,
		long donePayments,
		Instant createdAt,
		Instant updatedAt,
		List<PaymentView> payments
) {

	/** Compact per-payment view inside a batch status response. */
	public record PaymentView(
			String paymentId,
			CoffeeType coffeeType,
			PaymentStatus status,
			String remoteReference
	) {
		static PaymentView from(Payment payment) {
			return new PaymentView(
					payment.paymentId(),
					payment.coffeeType(),
					payment.status(),
					payment.remoteReference());
		}
	}

	static BatchStatusResponse from(PaymentBatch batch, List<Payment> payments) {
		long done = payments.stream().filter(p -> p.status() == PaymentStatus.DONE).count();
		return new BatchStatusResponse(
				batch.batchId(),
				batch.storeId(),
				batch.status(),
				batch.totalPayments(),
				done,
				batch.createdAt(),
				batch.updatedAt(),
				payments.stream().map(PaymentView::from).toList());
	}
}
