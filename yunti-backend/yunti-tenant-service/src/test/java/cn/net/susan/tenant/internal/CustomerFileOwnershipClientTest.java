package cn.net.susan.tenant.internal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CustomerFileOwnershipClientTest {

    @Test
    void shouldForwardBearerTokenWithoutCallerControlledOwnerId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CustomerFileOwnershipClient client = new CustomerFileOwnershipClient(builder, "http://customer");
        server.expect(once(), requestTo(
                        "http://customer/api/customer/internal/files/enterprise/900/ownership"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer signed-token"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"message\":\"成功\",\"data\":true,"
                                + "\"requestId\":null,\"timestamp\":1}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.requireOwnedBy(900L, "Bearer signed-token")).isTrue();
        server.verify();
    }
}
