package com.kaede.uspace.order.event;

import java.time.LocalDateTime;

/**
 * 一条订单开门成功（模块 8 发布，模块 11 的 QQ 群播报监听）。
 *
 * <h3>为什么这个类住在 order 包，而不是 qqbot 包</h3>
 *
 * <p>包依赖是单向的：{@code qqbot} 可以调其他模块，其他模块<b>禁止</b>认识 {@code qqbot}。
 * 若把事件类放进 {@code qqbot}，那 {@code OrderService} 要发布它就得 import {@code qqbot} ——
 * 单向依赖当场就断了，<b>而这件事在编译期没有任何提示</b>。
 * 所以事件住在发布方所在的包，由监听方反向 import。
 *
 * <h3>为什么只带这几个字段</h3>
 *
 * <p><b>不带昵称</b>：昵称要在播报里出现，但它属于「展示数据」，
 * 监听器在事务提交之后查一次库就能拿到最新值。塞进事件里反而会带来一个
 * 不报错的错误 —— 用户改了名，播报还念着旧的。
 *
 * <p><b>不带密码</b>：{@code passcode} 是门锁凭据。事件对象会被日志打印、
 * 被将来的监听器序列化，凭据一旦流出去没有任何补救手段。
 *
 * <p><b>不带门店</b>：当前是单门店，监听器要查在店名册时走的是
 * {@code InstoreService}，它自己会取「当前门店」。
 *
 * <p><b>刻意不带订单状态</b>：发布点就在 {@code IN_USE} 落库之后，
 * 带一个恒为 {@code IN_USE} 的字段只会诱使监听器去判断它。
 *
 * @param orderId   订单 ID
 * @param userId    下单用户 ID。监听器据此查昵称
 * @param orderNo   订单号。<b>只为日志</b>：两个模块的日志里 grep 同一个单号，
 *                  就能回答「这条播报为什么没发出去」
 * @param enteredAt 到店时刻（即订单的开始时刻，已截断到秒）
 */
public record OrderEnteredEvent(
        Long orderId,
        Long userId,
        String orderNo,
        LocalDateTime enteredAt) {
}
