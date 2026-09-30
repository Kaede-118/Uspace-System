package com.kaede.uspace.notice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发布手写公告的请求。
 *
 * <p><b>没有 {@code publishMode} 字段</b>：走到这个接口就是手写，
 * 不需要调用方声明，让调用方声明只会多一个能被传错的东西。
 * 与「{@code sourceType} / {@code sourceId} 不由入参提供」是同一个思路 ——
 * 凡是能推导出来、或本来就唯一确定的字段，都不做成入参。
 *
 * <p><b>没有生效/失效时刻</b>：公告是消息流，发出来就是可见的。
 * 想「到点才显示」这类需求，用一条消息表达本来就不自然 ——
 * 真要提前准备，写到点再发即可。
 */
@Data
public class CreateNoticeRequest {

    /**
     * 公告标题。必填。
     *
     * <p>长度上限 100 与库列一致。它是首页公告栏上显示的那一句话，
     * 太长在滚动条里显示成什么样不可控 —— 要展开说请写正文。
     */
    @NotBlank(message = "公告标题不能为空")
    @Size(max = 100, message = "公告标题不能超过 100 个字符")
    private String title;

    /**
     * 公告正文，可空。点开公告详情才看。
     *
     * <p>长度上限 500 与库列一致。
     */
    @Size(max = 500, message = "公告正文不能超过 500 个字符")
    private String content;

    /**
     * 是否置顶：1 置顶 / 0 不置顶。不传按 0 处理。
     *
     * <p>置顶的公告排在首页最前面，用来放「今天临时调整营业时间」这类
     * 必须让顾客先看到的消息。
     */
    private Integer pinned;

}
