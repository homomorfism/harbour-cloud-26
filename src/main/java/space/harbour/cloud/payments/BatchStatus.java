package space.harbour.cloud.payments;

/**
 * Status of a bulk payment request (a "batch") as a whole.
 *
 * <p>{@code PENDING} (no payment processed yet) -> {@code PROCESSING} (some, but
 * not all) -> {@code DONE} (every payment in the batch has been processed).
 */
public enum BatchStatus {
	PENDING,
	PROCESSING,
	DONE
}
