package space.harbour.cloud.payments;

/**
 * The external system that each payment must be registered into during async
 * processing. Implementations are expected to be idempotent on {@code paymentId}
 * so that a worker retry does not create a duplicate remote entry.
 */
public interface RemoteSystemClient {

	/**
	 * Creates (or returns the existing) entry for this payment in the remote
	 * system.
	 *
	 * @return the remote system's reference for the entry.
	 */
	String createEntry(Payment payment);
}
