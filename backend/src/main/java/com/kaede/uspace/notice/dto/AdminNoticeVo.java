package com.kaede.uspace.notice.dto;

import com.kaede.uspace.notice.NoticePublishMode;
import com.kaede.uspace.notice.NoticeSourceType;
import com.kaede.uspace.notice.entity.Notice;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运营后台的公告视图。
 *
 * <p>与用户端的 {@link NoticeVo} 的差别就是「多出运营才需要看的字段」：
 * 来源类型与来源 ID（用来回答「这条维护通知是哪台机器产生的」）、
 * 发布人，以及一个算出来的「能不能改」。
 *
 * <p><b>后台能看到来源 ID，用户端不能</b> —— 这不是随意划的线：
 * 运营需要它来定位问题机台，而顾客看到一个内部主键毫无用处，只是白白多暴露一点东西。
 */
@Data
public class AdminNoticeVo {

    /** 公告 ID */
    private Long id;

    /** 标题 */
    private String title;

    /** 正文，可为空 */
    private String content;

    /** 发布方式：{@code AUTO} / {@code MANUAL} */
    private String publishMode;

    /** 发布方式的中文 */
    private String publishModeText;

    /** 自动公告的来源类型，手写公告为 null */
    private String sourceType;

    /** 来源类型的中文 */
    private String sourceTypeText;

    /** 来源记录 ID（机台 ID）；手写公告为 null */
    private Long sourceId;

    /** 发布人（管理员 ID）；自动公告为 null */
    private Long createdBy;

    /** 这条消息的产生时刻 */
    private LocalDateTime createdAt;

    /**
     * 是否可编辑。
     *
     * <p>自动公告是已经发生的事实的记录，改写它等于篡改历史，所以只读。
     * 前端据此把按钮置灰，但<b>真正的拦截在 Service 层</b>（返回 40929）——
     * 前端置灰只是别让人白点一下，不是权限边界。
     */
    private boolean editable;

    /**
     * 由实体构造视图。
     *
     * @param notice 公告实体
     * @return 后台公告视图
     */
    public static AdminNoticeVo from(Notice notice) {
        AdminNoticeVo vo = new AdminNoticeVo();
        vo.setId(notice.getId());
        vo.setTitle(notice.getTitle());
        vo.setContent(notice.getContent());
        vo.setPublishMode(notice.getPublishMode());
        vo.setPublishModeText(NoticePublishMode.labelOf(notice.getPublishMode()));
        vo.setSourceType(notice.getSourceType());
        vo.setSourceTypeText(NoticeSourceType.labelOf(notice.getSourceType()));
        vo.setSourceId(notice.getSourceId());
        vo.setCreatedBy(notice.getCreatedBy());
        vo.setCreatedAt(notice.getCreatedAt());
        vo.setEditable(NoticePublishMode.MANUAL.name().equals(notice.getPublishMode()));
        return vo;
    }
}
