package space.harbour.cloud.payments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * REST API for registering StarHarbour coffee payments.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Validated
public class PaymentController {

	static final String STORE_ID_HEADER = "Store-Id";
	static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final PaymentService paymentService;

	public PaymentController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	/**
	 * Registers a payment for a coffee.
	 *
	 * <p>The store and idempotency token are passed as headers; the coffee
	 * details are passed as a JSON body.
	 *
	 * <p>Returns {@code 201 Created} for a brand-new payment and {@code 200 OK}
	 * when the same idempotency token has already been processed (the original
	 * payment is echoed back unchanged).
	 */
	@PostMapping
	public ResponseEntity<PaymentResponse> registerPayment(
			@RequestHeader(STORE_ID_HEADER) @NotBlank(message = "Store-Id header is required") String storeId,
			@RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody PaymentRequest request) {

		PaymentService.RegistrationResult result =
				paymentService.register(storeId, idempotencyKey, request);

		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(PaymentResponse.from(result.payment()));
	}

	/**
	 * Submits a bulk batch of payments for <em>asynchronous</em> processing.
	 *
	 * <p>The batch and its payments are persisted immediately and a background
	 * worker creates a remote-system entry for each one. The response is
	 * {@code 202 Accepted} with the batch id - the client polls
	 * {@code GET /api/v1/payments/batches/{batchId}} for completion.
	 */
	@PostMapping("/bulk")
	public ResponseEntity<BatchSubmissionResponse> submitBulk(
			@RequestHeader(STORE_ID_HEADER) @NotBlank(message = "Store-Id header is required") String storeId,
			@Valid @RequestBody BulkPaymentRequest request) {

		PaymentBatch batch = paymentService.submitBatch(storeId, request.payments());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(BatchSubmissionResponse.from(batch));
	}

	/**
	 * Reports the status of a bulk batch by its id: overall status plus a
	 * per-payment breakdown including the remote-system reference once done.
	 */
	@GetMapping("/batches/{batchId}")
	public BatchStatusResponse getBatch(@PathVariable String batchId) {
		return paymentService.findBatch(batchId)
				.map(view -> BatchStatusResponse.from(view.batch(), view.payments()))
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "No batch with id " + batchId));
	}

	/**
	 * Lists all payments for a given store.
	 */
	@GetMapping
	public List<PaymentResponse> listPayments(
			@RequestParam(name = "storeId") @NotBlank(message = "storeId query parameter is required") String storeId) {
		return paymentService.findByStoreId(storeId).stream()
				.map(PaymentResponse::from)
				.toList();
	}

	/**
	 * Reports how many payments live on each shard, keyed by shard index.
	 *
	 * <p>Handy for verifying that the hash router spreads payments roughly
	 * uniformly across the shard databases.
	 */
	@GetMapping("/shards/distribution")
	public Map<Integer, Long> shardDistribution() {
		return paymentService.shardDistribution();
	}

	/**
	 * Fetches a previously registered payment by its id.
	 */
	@GetMapping("/{paymentId}")
	public PaymentResponse getPayment(@PathVariable String paymentId) {
		return paymentService.findById(paymentId)
				.map(PaymentResponse::from)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "No payment with id " + paymentId));
	}
}
