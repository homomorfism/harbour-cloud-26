package space.harbour.cloud.payments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * The JSON body of a bulk "register many payments" request: a non-empty list of
 * the same single-payment bodies used by the synchronous endpoint.
 */
public record BulkPaymentRequest(

		@NotEmpty(message = "payments must contain at least one entry")
		List<@Valid PaymentRequest> payments
) {
}
