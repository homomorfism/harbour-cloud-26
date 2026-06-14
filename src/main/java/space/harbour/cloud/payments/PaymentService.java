package space.harbour.cloud.payments;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registers coffee payments, synchronously (single payment) or asynchronously
 * (a bulk batch that a background worker processes).
 *
 * <p>Synchronous registration is idempotent: the caller supplies an idempotency
 * token and, if the same token is seen twice for the same store, the original
 * payment is returned rather than a new one being created. This protects against
 * the client retrying after a network timeout - a classic at-least-once delivery
 * problem in distributed systems.
 */
@Service
public class PaymentService {

	private final PaymentRepository repository;
	private final PaymentBatchRepository batchRepository;
	private final Clock clock;

	public PaymentService(PaymentRepository repository,
						  PaymentBatchRepository batchRepository,
						  Clock clock) {
		this.repository = repository;
		this.batchRepository = batchRepository;
		this.clock = clock;
	}

	// --- Synchronous single payment -----------------------------------------

	/**
	 * Registers a payment for the given store.
	 *
	 * @return the result, flagging whether the payment was newly created or
	 *         replayed from a previous identical request.
	 */
	public RegistrationResult register(String storeId, String idempotencyKey, PaymentRequest request) {
		String effectiveKey = (idempotencyKey != null && !idempotencyKey.isBlank())
				? idempotencyKey
				: UUID.randomUUID().toString();
		Payment candidate = new Payment(
				paymentIdFor(storeId, effectiveKey),
				null,                     // not part of a batch
				storeId,
				request.coffeeType(),
				request.price(),
				request.currency(),
				request.loyaltyCardId(),
				effectiveKey,
				PaymentStatus.DONE,       // the sync path is already final
				null,
				clock.instant()
		);

		PaymentRepository.SaveResult result = repository.saveIfAbsent(candidate);
		return new RegistrationResult(result.payment(), result.created());
	}

	/**
	 * Derives the payment id deterministically from (storeId, idempotencyKey).
	 *
	 * <p>This is what makes idempotency and sharding compose cleanly: a retried
	 * request produces the <em>same</em> id, so it routes to the same shard and
	 * collides on the primary key there - the duplicate is caught without having
	 * to consult any other shard. For requests with no idempotency key the key is
	 * a random UUID, so the id is effectively random and unique.
	 */
	private static String paymentIdFor(String storeId, String effectiveKey) {
		String seed = storeId + "|" + effectiveKey;
		return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
	}

	// --- Asynchronous bulk batch --------------------------------------------

	/**
	 * Accepts a bulk request: persists the batch and all its payments as
	 * {@code PENDING} and returns immediately. The background
	 * {@link PaymentProcessor} does the actual work.
	 *
	 * @return the freshly created batch, whose id the client polls for status.
	 */
	public PaymentBatch submitBatch(String storeId, List<PaymentRequest> requests) {
		Instant now = clock.instant();
		String batchId = UUID.randomUUID().toString();

		PaymentBatch batch = new PaymentBatch(
				batchId, storeId, BatchStatus.PENDING, requests.size(), now, now);
		batchRepository.save(batch);

		List<Payment> payments = new ArrayList<>(requests.size());
		for (PaymentRequest request : requests) {
			payments.add(new Payment(
					UUID.randomUUID().toString(),
					batchId,
					storeId,
					request.coffeeType(),
					request.price(),
					request.currency(),
					request.loyaltyCardId(),
					UUID.randomUUID().toString(),
					PaymentStatus.PENDING,
					null,
					now));
		}
		repository.saveBatchPayments(payments);
		return batch;
	}

	/** The batch and its payments, for a status query. Empty if no such batch. */
	public Optional<BatchView> findBatch(String batchId) {
		return batchRepository.findById(batchId)
				.map(batch -> new BatchView(batch, repository.findByBatchId(batchId)));
	}

	/** A batch plus its (scatter-gathered) payments. */
	public record BatchView(PaymentBatch batch, List<Payment> payments) {
	}

	// --- Lookups ------------------------------------------------------------

	/**
	 * Looks up a previously registered payment by its id.
	 */
	public Optional<Payment> findById(String paymentId) {
		return repository.findById(paymentId);
	}

	public List<Payment> findByStoreId(String storeId) {
		return repository.findByStoreId(storeId);
	}

	/** Row count per shard index - exposes how evenly payments are distributed. */
	public Map<Integer, Long> shardDistribution() {
		return repository.countByShard();
	}

	/**
	 * @param payment the persisted payment
	 * @param created true if this call created the payment, false if it was a
	 *                replay of an earlier request with the same idempotency token
	 */
	public record RegistrationResult(Payment payment, boolean created) {
	}
}
