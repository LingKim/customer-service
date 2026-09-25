package cn.net.susan.realtime.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 长连接消息信封（上下行共用）。
 *
 * <p>上行：PING / JOIN / SEND / HISTORY / CLOSE_SESSION<br/>
 * 上行（工作台协同）：CLAIM 认领、RELEASE 退回队列、TRANSFER 转接、NOTE 内部备注<br/>
 * 下行：CONNECTED / PONG / JOINED / ACK / MESSAGE / HISTORY / SESSION / PRESENCE / AGENTS / QUEUE / ERROR<br/>
 * 其中 QUEUE 是"会话列表有变化"的轻量提醒（新会话排队、访客新消息、状态流转），
 * 坐席收到后重新拉取列表即可，不承载业务数据。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeMessage(
        String type,
        String sessionNo,
        String clientMsgNo,
        Integer msgType,
        String content,
        Long beforeId,
        Long toAgentId,
        String remark,
        Object data,
        Long serverTime,
        Integer code,
        String message
) {

    public static RealtimeMessage of(String type) {
        return new RealtimeMessage(type, null, null, null, null, null, null, null, null,
                System.currentTimeMillis(), null, null);
    }

    public static RealtimeMessage error(int code, String message) {
        return new RealtimeMessage("ERROR", null, null, null, null, null, null, null, null,
                System.currentTimeMillis(), code, message);
    }

    public static RealtimeMessage withData(String type, String sessionNo, Object data) {
        return new RealtimeMessage(type, sessionNo, null, null, null, null, null, null, data,
                System.currentTimeMillis(), null, null);
    }
}
