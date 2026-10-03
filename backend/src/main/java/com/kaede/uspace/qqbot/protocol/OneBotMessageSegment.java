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

    /** 段类型：{@code text} / {@code image} / {@code at} / {@code face} …… */
    private String type;

    /** 该类型自己的字段。见类注释 */
    private Map<String, Object> data;
}
