package space.harbour.cloud.payments;

/**
 * Lifecycle of a single payment in the async flow.
 *
 * <p>{@code PENDING} -> claimed as {@code PROCESSING} by a worker -> {@code DONE}
 * once its entry has been created in the remote system. Payments registered via
 * the synchronous single-payment endpoint are stored already {@code DONE}.
 */
public enum PaymentStatus {
	PENDING,
	PROCESSING,
	DONE
}
