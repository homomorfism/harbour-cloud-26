package space.harbour.cloud.payments;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the full async path against the three in-memory H2 shards: submit a
 * bulk batch, get an id back immediately, and watch the background worker drive
 * every payment to DONE (each with a remote-system reference).
 */
@SpringBootTest
class BatchFlowTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc() {
		return MockMvcBuilders.webAppContextSetup(context).build();
	}

	private static final String BULK_BODY = """
			{
			  "payments": [
			    {"coffeeType": "LATTE",      "price": 3.50, "currency": "EUR", "loyaltyCardId": "card-1"},
			    {"coffeeType": "ESPRESSO",   "price": 2.00, "currency": "EUR", "loyaltyCardId": "card-2"},
			    {"coffeeType": "CAPPUCCINO", "price": 3.00, "currency": "EUR", "loyaltyCardId": "card-3"},
			    {"coffeeType": "FLAT_WHITE", "price": 3.20, "currency": "EUR", "loyaltyCardId": "card-4"},
			    {"coffeeType": "MOCHA",      "price": 3.80, "currency": "EUR", "loyaltyCardId": "card-5"}
			  ]
			}
			""";

	@Test
	void bulkSubmissionIsAcceptedThenProcessedToDone() throws Exception {
		MockMvc mvc = mockMvc();

		// 1. Submit the batch - accepted immediately, still PENDING, with an id.
		String submitted = mvc.perform(post("/api/v1/payments/bulk")
						.header(PaymentController.STORE_ID_HEADER, "store-bulk-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(BULK_BODY))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.batchId").exists())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.totalPayments").value(5))
				.andReturn().getResponse().getContentAsString();

		String batchId = JsonPath.read(submitted, "$.batchId");

		// 2. Poll the status endpoint until the worker finishes the batch.
		Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
		String last = "";
		while (Instant.now().isBefore(deadline)) {
			last = mvc.perform(get("/api/v1/payments/batches/{id}", batchId))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			if ("DONE".equals(JsonPath.read(last, "$.status"))) {
				break;
			}
			Thread.sleep(250);
		}

		// 3. Every payment processed, each with a remote reference.
		assertEquals("DONE", JsonPath.read(last, "$.status"), "batch did not reach DONE in time: " + last);
		assertEquals(Integer.valueOf(5), JsonPath.read(last, "$.donePayments"),
				"not all payments done: " + last);
		String body = last;
		java.util.List<String> refs = JsonPath.read(body, "$.payments[*].remoteReference");
		assertEquals(5, refs.size());
		refs.forEach(ref -> {
			if (ref == null || !ref.startsWith("remote-")) {
				fail("payment missing a remote reference: " + body);
			}
		});
	}

	@Test
	void emptyBulkRequestIsRejected() throws Exception {
		mockMvc().perform(post("/api/v1/payments/bulk")
						.header(PaymentController.STORE_ID_HEADER, "store-bulk-2")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"payments\": []}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void unknownBatchIdReturns404() throws Exception {
		mockMvc().perform(get("/api/v1/payments/batches/{id}", "does-not-exist"))
				.andExpect(status().isNotFound());
	}
}
