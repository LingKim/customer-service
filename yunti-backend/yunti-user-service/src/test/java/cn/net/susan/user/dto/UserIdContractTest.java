package cn.net.susan.user.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserIdContractTest {

    @Test
    void publicSnowflakeIdsUseStringType() throws NoSuchMethodException {
        assertThat(LoginResponse.class.getMethod("userId").getReturnType()).isEqualTo(String.class);
        assertThat(RegisterResponse.class.getMethod("userId").getReturnType()).isEqualTo(String.class);
        assertThat(RegisterResponse.class.getMethod("enterpriseId").getReturnType()).isEqualTo(String.class);
        assertThat(MeResponse.class.getMethod("userId").getReturnType()).isEqualTo(String.class);
    }
}
