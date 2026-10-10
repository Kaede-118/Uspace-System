package com.kaede.uspace.order.event;

import java.math.BigDecimal;

/**
 * 一笔付款凭证进了人工复核队列（模块 8 → 通知运营）。
 *
 * <p><b>为什么要有这条事件</b>（2026-10-10 加）：小额收款在识别到交易单号时
 * 当场自动结清、不打扰任何人；<b>没识别到单号的、以及月卡包场这类大额收款</b>
 * 则一律落到人工复核。而人工复核最大的风险是「没人记得去看」——
 * 顾客以为付完了，钱却一直挂在待复核上。所以凭证一进队列就推一条到店主群，
 * 把「记得去后台看」这件事交给播报。
 *
 * <p>⚠️ <b>只推店主群</b>（{@code QqbotProperties#isAmountVisible}），
 * 因为这条一定带金额 —— 顾客群里说「某某提交了一笔 40 元的凭证」是泄露。
 *
 * <p>发布点在 {@code PaymentProofService#submit}，监听器取
 * {@code AFTER_COMMIT} 相位（钱那一步已经落定了才播）。
 *
 * @param proofId    凭证 ID，便于日志核对
 * @param targetType 收款类型名（ORDER / PRODUCT / BOOKING / MONTHLY_CARD）
 * @param orderNo    对外单号
 * @param amount     应付金额（元）
 * @param reason     为什么会进人工复核，一句短语（如「未识别到交易单号」「该类收款需人工复核」）
 */
public record PaymentProofPendingEvent(Long proofId, String targetType, String orderNo,
                                       BigDecimal amount, String reason) {
}
