package com.kaede.uspace.notice.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import com.kaede.uspace.notice.NoticePublishMode;
import com.kaede.uspace.notice.NoticeSourceType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公告实体，对应 {@code biz_notice} 表。
 *
 * <p><b>这是一条消息，不是一个状态</b> —— 这一点决定了本表几乎所有的设计：
 * <ul>
 *   <li><b>只增不改</b>。自动公告是「什么时刻发生了什么事」的记录，
 *       机台从良好转成维护中产生了这一条，它就一直躺在这里；
 *       机台修好不会把这条删掉或改掉，而是<b>再产生一条新的</b>。
 *       于是首页公告栏读起来是一段历史，而不是一份当前状态的快照</li>
 *   <li><b>没有生效/失效时刻</b>。一条消息的「有效期」是说不通的 ——
 *       「3 号机转维护中」这件事发生在那一刻，它不需要「从明天起生效」，
 *       也不会「下周三自动失效」。发生时刻就是 {@code createdAt}</li>
 *   <li><b>没有唯一键约束</b>。同一台机台可以反复出现在公告里，
 *       每次状态变化各占一条 —— 幂等在这里恰恰是要避免的行为</li>
 *   <li><b>最新的在最上面</b>。倒序即时间线，不需要置顶、权重、
 *       排序值那一套 —— 消息流里「我想让这条排前面」不是一个真实需求</li>
 * </ul>
 *
 * <p>两类公告靠 {@link #publishMode} 区分：
 * {@code AUTO} 由业务模块在事件发生时写入（如机台状况变化），
 * {@code MANUAL} 由管理员手写。前者只读，后者可改可删。
 *
 * <p><b>包场不在这里</b>：包场信息（哪个时段被包了）走独立的「包场时间表」，
 * 不混进消息流 —— 它是「未来的安排」而不是「已经发生的事」，
 * 两者混在一起会让用户分不清哪条是通知、哪条是日程。
 *
 * @see com.kaede.uspace.notice.NoticePublishMode 发布方式
 * @see com.kaede.uspace.notice.NoticeSourceType 来源类型（用来追溯是哪台机台）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_notice")
public class Notice extends BaseEntity {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /**
     * 公告标题。一句话，首页公告栏上显示的就是它。
     *
     * <p>自动公告的标题由 {@code NoticeContents} 生成 —— 那边刻意让方法签名
     * 不接受机台备注之类的运营内部信息，从结构上防止越界披露。
     *
     * <p>长度上限 100 与库列一致：它是滚动条上的一句话，
     * 太长在滚动条里显示成什么样不可控。要展开说请写正文。
     */
    private String title;

    /**
     * 公告正文，可为 null。
     *
     * <p>自动公告一律为 null —— 一条事件通知一句话就够了。
     * 正文是给管理员手写公告用的（活动说明、临时通知）。
     */
    private String content;

    /**
     * 发布方式，取值见 {@link NoticePublishMode}。
     *
     * <p>存字符串而不是枚举类型：与 {@code biz_order.status} 同一套做法，
     * 库里存的就是人可读的名字，排查数据时不必回查对照表。
     */
    private String publishMode;

    /**
     * 自动公告的来源类型，手写公告为 null。取值见 {@link NoticeSourceType}。
     *
     * <p><b>不再是唯一键的一部分</b>：同一台机台会产生很多条公告，
     * 这两列只用来回答「这条公告是哪台机器产生的」。
     */
    private String sourceType;

    /**
     * 来源记录 ID（机台 ID），手写公告为 null。
     *
     * <p><b>不对外暴露</b>：用户端的 {@code NoticeVo} 里没有这个字段。
     * 顾客看到「拍拍机 1 号 转为维护中」就够了，不需要知道它在库里的主键。
     */
    private Long sourceId;

    /** 发布人（管理员 ID）。自动公告为 null */
    private Long createdBy;
}
