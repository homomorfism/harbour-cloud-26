package space.harbour.cloud.payments;

/**
 * Immediate reply to a bulk submission: the id the client uses to poll status,
 * returned before any payment has actually been processed.
 */
public record BatchSubmissionResponse(
		String batchId,
		BatchStatus status,
		int totalPayments
) {
	static BatchSubmissionResponse from(PaymentBatch batch) {
		return new BatchSubmissionResponse(batch.batchId(), batch.status(), batch.totalPayments());
	}
}
