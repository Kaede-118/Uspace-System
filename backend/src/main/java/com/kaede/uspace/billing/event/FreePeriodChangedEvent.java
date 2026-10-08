package com.kaede.uspace.billing.event;

import java.time.LocalDateTime;

/**
 * 免费活动被新增或撤销（模块 7 发布，模块 11 的 QQ 群播报监听）。
 *
 * <h3>为什么这个类住在 billing 包</h3>
 *
 * <p>包依赖是单向的：{@code qqbot} 可以调其他模块，其他模块<b>禁止</b>认识 {@code qqbot}。
 * 发布点是 {@code FreePeriodService}（住在 billing 包），所以事件也住在 billing 包。
 * 注意它<b>不是</b> space 包的事件 —— 免费时段虽然挂在
 * {@code /api/admin/store/free-periods} 路径下（由 space 侧的 Controller 转发
 * storeId），但它的归属是<b>计费规则</b>（模块 7），与停业那种准入规则不是一回事
 * （见「计费规则」一节的免费时段小节）。
 *
 * <h3>为什么撤销也要播</h3>
 *
 * <p>「今晚 20:00–次日 02:00 免费」进了群之后，总会有人为它跑一趟。
 * 撤销若不播，这些人到了才发现不免费 —— 那比从没说过更让人不快。
 *
 * @param periodId 活动记录 ID
 * @param startAt  活动开始时刻
 * @param endAt    活动结束时刻
 * @param reason   活动名称 / 原因，可为 null（后台表单可不填）
 * @param action   新增还是撤销
 */
public record FreePeriodChangedEvent(
        Long periodId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String reason,
        FreePeriodChangeAction action) {
}
