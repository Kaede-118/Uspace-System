package com.kaede.uspace.order.event;

/**
 * 一笔商品购买完成（模块 8 发布，模块 11 的 QQ 群播报监听）。
 *
 * <h3>为什么这个类住在 order 包，而不是 qqbot 包</h3>
 *
 * <p>包依赖是单向的：{@code qqbot} 可以调其他模块，其他模块<b>禁止</b>认识 {@code qqbot}。
 * 发布点是 {@code ProductPaymentTargetHandler#markPaid}（住在 order 包），
 * 所以事件也住在 order 包，由监听方反向 import 一一
 * 与 {@link OrderEnteredEvent} 同一条规矩。
 *
 * <h3>发布时机＝「钱确实收到了」的那一刻</h3>
 *
 * <p>商品的付款有两条路：线上通道回调、以及扫码转账下用户上传付款截图后当场落账。
 * 两条都汇聚到 {@code markPaid} —— 发布点选在那里，一次覆盖两条，
 * 与包场播报（{@code BookingActivatedEvent}）同源。
 * <b>下单时（还停在待支付）刻意不播</b>：单子随时可能被取消，提前说就成了假消息。
 *
 * <h3>为什么只带这几个字段</h3>
 *
 * <p><b>不带昵称</b>：昵称要在播报里出现，但它属于「展示数据」，
 * 监听器在事务提交之后查一次库就能拿到最新值。塞进事件里反而会带来一个
 * 不报错的错误 —— 用户改了名，播报还念着旧的。
 *
 * <p><b>不带金额</b>：这条播报对所有群发同一份文本，金额不进群 ——
 * 与到店、包场播报同一条披露边界。
 *
 * <p><b>商品名带的是购买单上的快照</b>：他买的就是这个名字。
 * 事后管理员改了商品名，播报仍与单据一致（与 {@code biz_product_order}
 * 存 {@code product_name} 快照是同一个理由）。
 *
 * @param orderId     购买单 ID
 * @param userId      购买人用户 ID。监听器据此查昵称
 * @param orderNo     购买单号。<b>只为日志</b>：两个模块的日志里 grep 同一个单号，
 *                    就能回答「这条播报为什么没发出去」
 * @param productId   商品 ID。监听器据此查「还剩多少」
 * @param productName 商品名（下单时的快照）
 * @param quantity    购买数量（可为 null，文案层按 1 件兜底）
 */
public record ProductPurchasedEvent(
        Long orderId,
        Long userId,
        String orderNo,
        Long productId,
        String productName,
        Integer quantity) {
}
