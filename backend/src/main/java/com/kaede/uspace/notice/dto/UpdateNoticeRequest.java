package com.kaede.uspace.notice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改手写公告的请求。
 *
 * <p><b>只能改这两个字段，这是有意的</b>：
 * <ul>
 *   <li>没有 {@code publishMode} —— 手写公告不会因为一次编辑就变成自动的。
 *       发布方式是它的身份，不是可改的属性</li>
 *   <li>没有 {@code sourceType} / {@code sourceId} —— 手写公告的这两列恒为 null</li>
 *   <li>没有 {@code createdBy} —— 改内容不等于换个人发布</li>
 *   <li>没有 {@code createdAt} —— 那是这条消息产生的时刻，改它等于伪造时间</li>
 * </ul>
 *
 * <p><b>自动公告根本不接受这个请求</b>：Service 层会先查出来判断
 * {@code publishMode}，是 {@code AUTO} 就返回 40929。
 */
@Data
public class UpdateNoticeRequest {

    /** 公告标题。必填，与全量替换的语义保持一致 */
    @NotBlank(message = "公告标题不能为空")
    @Size(max = 100, message = "公告标题不能超过 100 个字符")
    private String title;

    /**
     * 公告正文。
     *
     * <p><b>传 null 表示清空正文</b> —— 与 {@code UpdateProfileRequest} 的全量替换
     * 语义一致（传 null 即清空该项），不是「不修改」。前端提交时会把当前值一起带上。
     */
    @Size(max = 500, message = "公告正文不能超过 500 个字符")
    private String content;
}
