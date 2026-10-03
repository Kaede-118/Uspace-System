package com.kaede.uspace.qqbot.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 协议端对 Action 的响应（OneBot v11 协议映射层）。
 *
 * <p><b>它从与事件相同的那条 WebSocket 回来</b>，所以接收端必须能区分两者：
 * 事件有 {@code post_type}，响应没有，却有 {@code status} / {@code retcode}。
 * 分流写在 {@code OneBotWebSocketHandler}。
 *
 * <p>骨架阶段对响应的处理很轻：<b>只记日志，不做请求-响应配对</b>。
 * 理由是机器人所有出站都是「发一条群消息」这种发出去就不回头的事，
 * 没有需要等结果的调用；维护一张 {@code echo → CompletableFuture} 的表
 * 意味着还要处理超时、连接断开时的批量失败，那套复杂度现在换不来任何东西。
 * 将来真需要（比如发消息后要拿 {@code message_id} 去撤回）再加。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OneBotActionResponse {

    /** {@code status} 的成功取值 */
    public static final String STATUS_OK = "ok";

    /**
     * 执行结果：{@code ok} / {@code failed}。
     *
     * <p>⚠️ <b>判断成功要用这个字段，不要用 {@code retcode == 0}</b>：
     * 两者在规范里是一致的，但 {@code retcode} 是数字、缺失时会反序列化成 null，
     * 而 null 拆箱比 0 会抛 NPE —— 一个「响应格式变了就炸」的隐患。
     * 字符串比较遇到缺失只会得到 false，与「失败」的处理路径一致。
     */
    private String status;

    /** 返回码，0 表示成功。非 0 时含义见 OneBot 规范的错误码表 */
    private Integer retcode;

    /**
     * 返回数据。用 {@link JsonNode} 装而不建一个类 ——
     * 不同 action 的 data 结构完全不同（发消息返回 {@code message_id}，
     * 查群成员返回一个数组），为它们各建一个类没有意义，反正现在也没读。
     */
    private JsonNode data;

    /** 原样带回的 {@code echo}，用于对上「这是哪一次发送的响应」*/
    private String echo;

    /**
     * 这次调用是否成功。
     *
     * @return {@code status} 为 {@code ok} 时返回 true；字段缺失或为其他值都算失败
     */
    public boolean isOk() {
        return STATUS_OK.equals(status);
    }
}
