package com.kaede.uspace.notice.dto;

import com.kaede.uspace.notice.NoticePublishMode;
import com.kaede.uspace.notice.entity.Notice;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户端的公告视图 —— 消息流里的一行。
 *
 * <p><b>它是白名单，不是黑名单</b>：只有这里声明过的字段才会返回给前端。
 * 与 {@code UserProfileVo} 同一套做法 —— 因此不存在「新加了敏感字段却忘了排除」的风险。
 *
 * <p><b>绝对不出的字段：{@code sourceType}、{@code sourceId}、{@code createdBy}。</b>
 * 顾客看到「拍拍机 1 号 由 良好 转为 维护中」就够了，
 * 不需要知道它在库里的主键，也不需要知道是哪个管理员点的按钮。
 *
 * <p><b>没有生效/失效时刻</b>：公告没有有效期，{@code createdAt} 就是发生时刻，
 * 前端拿它显示「3 分钟前」。首页按 id 倒序展示，最新的在最上面。
 */
@Data
public class NoticeVo {

    /** 公告 ID */
    private Long id;

    /** 标题。首页公告栏显示的就是它 */
    private String title;

    /** 正文，可为 null。自动公告恒为 null，只有管理员手写的才有 */
    private String content;

    /** 发布方式：{@code AUTO} 系统自动 / {@code MANUAL} 管理员手写 */
    private String publishMode;

    /** 发布方式的中文，供前端直接渲染，省得再维护一份映射表 */
    private String publishModeText;

    /**
     * 这条消息的产生时刻。
     *
     * <p>前端拿它显示相对时间（「3 分钟前」「昨天」）——
     * 一条事件通知如果只有标题没有时间，读起来像一份永不过期的状态声明。
     */
    private LocalDateTime createdAt;

    /**
     * 由实体构造视图。
     *
     * <p>写成静态工厂而不是构造函数，是为了让调用处读起来有语义。
     *
     * @param notice 公告实体
     * @return 对外的公告视图
     */
    public static NoticeVo from(Notice notice) {
        NoticeVo vo = new NoticeVo();
        vo.setId(notice.getId());
        vo.setTitle(notice.getTitle());
        vo.setContent(notice.getContent());
        vo.setPublishMode(notice.getPublishMode());
        vo.setPublishModeText(NoticePublishMode.labelOf(notice.getPublishMode()));
        vo.setCreatedAt(notice.getCreatedAt());
        return vo;
    }
}
