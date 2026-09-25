package cn.net.susan.realtime.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 长连接消息信封（上下行共用）。
 *
 * <p>上行：PING / JOIN / SEND / HISTORY / CLOSE / CLOSE_SESSION<br/>
 * 下行：CONNECTED / PONG / JOINED / ACK / MESSAGE / HISTORY / SESSION / PRESENCE / ERROR</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeMessage(
        String type,
        String sessionNo,
        String clientMsgNo,
        Integer msgType,
        String content,
        Long beforeId,
        Object data,
        Long serverTime,
        Integer code,
        String message
) {

    public static RealtimeMessage of(String type) {
        return new RealtimeMessage(type, null, null, null, null, null, null, System.currentTimeMillis(), null, null);
    }

    public static RealtimeMessage error(int code, String message) {
        return new RealtimeMessage("ERROR", null, null, null, null, null, null,
                System.currentTimeMillis(), code, message);
    }

    public static RealtimeMessage withData(String type, String sessionNo, Object data) {
        return new RealtimeMessage(type, sessionNo, null, null, null, null, data,
                System.currentTimeMillis(), null, null);
    }
}
