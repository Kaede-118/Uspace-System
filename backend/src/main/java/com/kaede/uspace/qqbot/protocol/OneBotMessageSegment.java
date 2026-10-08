package com.kaede.uspace.qqbot.protocol;

import lombok.Data;

import java.util.Map;

/**
 * OneBot 消息段的协议映射（模块 11）。
 *
 * <p>群消息的 {@code message} 字段是一个<b>段数组</b>：一句话由若干段拼成 ——
 * 文本是一段、@ 是一段、图片也是一段。例如：
 * <pre>
 * [{"type":"text",  "data":{"text":"看看"}},
 *  {"type":"image", "data":{"file":"xxx.jpg","url":"http://...","file_size":"12345"}}]
 * </pre>
 *
 * <p><b>指令解析不走这里</b>（那是 {@code raw_message} 的活，见 {@link OneBotEvent}）。
 * 本类只为一件 {@code raw_message} 做不到的事而存在：<b>拿到图片</b> ——
 * CQ 码里只有个文件名，而段里带着可下载的 {@code url}。
 *
 * <p>各类型的 {@code data} 字段互不相同（文本是 {@code text}、图片是
 * {@code file} / {@code url}、@ 是 {@code qq}），所以用 Map 装而不为每种段建一个类 ——
 * 与 {@code OneBotAction.params} 是同一个理由。
 */
@Data
public class OneBotMessageSegment {

    /** 文本段 */
    public static final String TYPE_TEXT = "text";

    /** 图片段 */
    public static final String TYPE_IMAGE = "image";

    /**
     * @ 某人段。
     *
     * <p><b>出站方向靠它主动 @ 人</b> —— 那是 {@code auto_escape=true} 的纯文本
     * 消息做不到的事（CQ 码会被转义成字面文本，见 {@code OneBotAction}）。
     */
    public static final String TYPE_AT = "at";

    /** 段类型：{@code text} / {@code image} / {@code at} / {@code face} …… */
    private String type;

    /** 该类型自己的字段。见类注释 */
    private Map<String, Object> data;

    /**
     * 造一个文本段。
     *
     * @param text 文本内容。<b>不会被当作 CQ 码解析</b>，所以把用户昵称放进来是安全的
     * @return 文本段
     */
    public static OneBotMessageSegment text(String text) {
        return of(TYPE_TEXT, Map.of("text", text));
    }

    /**
     * 造一个 @ 段。
     *
     * <p>⚠️ <b>{@code qq} 用字符串而不是数字</b>：OneBot v11 规范里它的类型是
     * {@code string}。多数实现也认数字，但按规范写不必去赌对端的宽容度。
     *
     * @param qq 要 @ 的 QQ 号，不可为 null
     * @return @ 段
     */
    public static OneBotMessageSegment at(String qq) {
        return of(TYPE_AT, Map.of("qq", qq));
    }

    /**
     * 造一个段。
     *
     * <p>用 {@code Map.of} 意味着 data 里的值不能为 null ——
     * 这对本项目的三种段都成立（各自的字段由调用方保证有值）。
     *
     * @param type 段类型
     * @param data 该类型的字段
     * @return 段
     */
    private static OneBotMessageSegment of(String type, Map<String, Object> data) {
        OneBotMessageSegment segment = new OneBotMessageSegment();
        segment.setType(type);
        segment.setData(data);
        return segment;
    }
}
