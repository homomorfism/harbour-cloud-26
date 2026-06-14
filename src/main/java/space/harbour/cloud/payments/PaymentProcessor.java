package space.harbour.cloud.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Background worker that drives the async flow forward, on a fixed schedule.
 *
 * <p>Two steps each tick:
 * <ol>
 *   <li><b>Process payments</b> - claim each pending batch payment, create its
 *       entry in the remote system, and mark it {@code DONE}.</li>
 *   <li><b>Reconcile batches</b> - recompute each unfinished batch's status from
 *       how many of its payments are done, flipping it to {@code DONE} once all
 *       are processed.</li>
 * </ol>
 *
 * <p>Runs are non-overlapping ({@code fixedDelay} waits for the previous tick),
 * and each payment is claimed with an atomic {@code PENDING -> PROCESSING}
 * update, so no payment is ever sent to the remote system twice.
 */
@Component
public class PaymentProcessor {

	private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

	private final PaymentRepository payments;
	private final PaymentBatchRepository batches;
	private final RemoteSystemClient remoteSystem;
	private final Clock clock;

	public PaymentProcessor(PaymentRepository payments,
							PaymentBatchRepository batches,
							RemoteSystemClient remoteSystem,
							Clock clock) {
		this.payments = payments;
		this.batches = batches;
		this.remoteSystem = remoteSystem;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${payments.worker.interval-ms:1000}")
	public void tick() {
		processPendingPayments();
		reconcileBatches();
	}

	private void processPendingPayments() {
		List<Payment> pending = payments.findPendingBatchPayments();
		for (Payment payment : pending) {
			if (!payments.claim(payment.paymentId())) {
				continue; // another worker/tick already took it
			}
			try {
				String reference = remoteSystem.createEntry(payment);
				payments.markDone(payment.paymentId(), reference);
			} catch (RuntimeException e) {
				log.warn("Failed to process payment {}, releasing for retry", payment.paymentId(), e);
				payments.releaseClaim(payment.paymentId());
			}
		}
	}

	private void reconcileBatches() {
		for (PaymentBatch batch : batches.findUnfinished()) {
			long done = payments.countDoneInBatch(batch.batchId());
			BatchStatus next = statusFor(done, batch.totalPayments());
			if (next != batch.status()) {
				batches.updateStatus(batch.batchId(), next, clock.instant());
				log.info("Batch {} -> {} ({}/{} payments done)",
						batch.batchId(), next, done, batch.totalPayments());
			}
		}
	}

	private static BatchStatus statusFor(long done, int total) {
		if (done >= total) {
			return BatchStatus.DONE;
		}
		return done == 0 ? BatchStatus.PENDING : BatchStatus.PROCESSING;
	}
}
