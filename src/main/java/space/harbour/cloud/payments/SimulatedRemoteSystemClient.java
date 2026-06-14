package space.harbour.cloud.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Stand-in for a real remote system (in a real deployment this would be an HTTP
 * call to a third-party ledger / accounting service). It fabricates a reference
 * id, optionally simulating call latency so the async processing is observable.
 *
 * <p>It is idempotent on {@code paymentId}: asking twice for the same payment
 * returns the same reference, mimicking a remote API that de-duplicates on an
 * idempotency key. That is what makes worker retries safe.
 */
@Component
public class SimulatedRemoteSystemClient implements RemoteSystemClient {

	private static final Logger log = LoggerFactory.getLogger(SimulatedRemoteSystemClient.class);

	private final ConcurrentMap<String, String> entriesByPaymentId = new ConcurrentHashMap<>();
	private final long latencyMillis;

	public SimulatedRemoteSystemClient(
			@Value("${payments.remote.latency-ms:0}") long latencyMillis) {
		this.latencyMillis = latencyMillis;
	}

	@Override
	public String createEntry(Payment payment) {
		return entriesByPaymentId.computeIfAbsent(payment.paymentId(), id -> {
			if (latencyMillis > 0) {
				sleep();
			}
			String reference = "remote-" + UUID.randomUUID();
			log.info("Remote system: created entry {} for payment {}", reference, id);
			return reference;
		});
	}

	private void sleep() {
		try {
			Thread.sleep(latencyMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while calling remote system", e);
		}
	}
}
