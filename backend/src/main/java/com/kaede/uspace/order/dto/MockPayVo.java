package com.kaede.uspace.order.dto;

import lombok.Data;

/**
 * 模拟支付的结果（仅 {@code provider=mock} 时存在）。
 *
 * <p>刻意把「用户已付款」与「回调已投递」分成两件事返回：
 * 演示「回调丢失」时，用户那边确实付了钱（{@code paid=true}），
 * 而回调被丢弃（{@code notified=false}），订单因此仍停在待支付 ——
 * 这正是生产环境会出现的情形，也是本模块提供主动查单补偿的理由。
 */
@Data
public class MockPayVo {

    /** 模拟的支付动作是否已完成（相当于「用户确实付了钱」） */
    private boolean paid;

    /**
     * 回调是否真的投递到了本系统。
     *
     * <p>为 false 通常是因为配置了 {@code uspace.payment.mock.notify-drop-rate}，
     * 用于演示「钱付了、回调没到」。此时订单仍是待支付状态，
     * 调一次「查询支付状态」就能补上。
     */
    private boolean notified;

    /** 给演示者看的一句话说明 */
    private String message;
}
