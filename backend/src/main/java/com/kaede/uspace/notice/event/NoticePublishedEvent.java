package com.kaede.uspace.notice.event;

import com.kaede.uspace.notice.NoticePublishMode;

/**
 * 一条公告发布（公告包发布，模块 11 的 QQ 群播报监听）。
 *
 * <h3>两类公告都发本事件</h3>
 *
 * <p>手写公告（管理员主动发声）与自动公告（机台转维护这类系统记录）各发一次。
 * 两类的播报文案<b>确实分叉</b>：手写的带「门店公告：」前缀、正文与详情链接；
 * 自动的标题本身就是一句完整的事件描述（「3 号机台由 良好 转为 维护中」），
 * 直接发即可，且它没有正文、点进去也只是列表页，所以不带链接 ——
 * 这正是 {@code publishMode} 要带上的原因，而不是一个恒量字段。
 *
 * <h3>为什么这个类住在 notice 包</h3>
 *
 * <p>与 {@code order.event} 下的三个事件同一条规矩：事件住在<b>发布方</b>所在的包，
 * 由监听方（{@code qqbot}）反向 import。{@code notice} 是「只依赖 common」的
 * 独立包（公告要展示机台状况，落进 {@code space} 会与 {@code device} 成环），
 * 更不可能反过来 import {@code qqbot}。
 *
 * <h3>⚠️ 本事件可能在事务之外被监听</h3>
 *
 * <p>{@code NoticeService#publishAuto} <b>刻意不标 {@code @Transactional}</b>：
 * 它是给别的模块当副作用调的，契约是「与调用方同事务」而不是「自己开事务」。
 * 因此监听器那边写了 {@code fallbackExecution = true}（详见
 * {@code QqBroadcastListener#onNoticePublished} 的注释）——
 * 少了它，将来任何在事务外调用 {@code publishAuto} 的模块都会遇到
 * 「公告落库成功、群里一条不播、零报错」。
 *
 * <h3>为什么带 content</h3>
 *
 * <p>手写公告的正文往往才是重点（标题「本周六场地维护」+ 正文写具体时段），
 * 只播标题会丢信息。自动公告的 content 恒为 null，文案侧判空跳过即可。
 *
 * @param noticeId    公告 ID，只为日志
 * @param title       公告标题 —— 播报的主体
 * @param content     公告正文，可为 null（自动公告恒为 null）
 * @param publishMode 发布方式（MANUAL / AUTO）—— 两类的文案不同
 */
public record NoticePublishedEvent(
        Long noticeId,
        String title,
        String content,
        NoticePublishMode publishMode) {
}
