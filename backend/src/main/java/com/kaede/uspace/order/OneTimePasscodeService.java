package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.lock.LockService;
import com.kaede.uspace.lock.dto.GetOneTimePasscodeRequest;
import com.kaede.uspace.lock.dto.OneTimePasscodeResult;
import com.kaede.uspace.order.dto.OneTimePasscodeVo;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.order.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 一次性密码的发放（模块 11 的群指令 {@code fw开门} 用）。
 *
 * <p>它只为问答一件事：<b>「给这张订单取一串新的、用一次即焚的门锁密码」</b>。
 *
 * <h3>为什么不塞进 OrderService</h3>
 *
 * <p>{@link OrderService} 已经一千七百多行，而本类的职责与订单生命周期无关 ——
 * 它不碰状态机、不算钱、不发事件，只是「调云取密码 + 记一笔」。
 *
 * <h3>为什么每次都重新取，不做复用</h3>
 *
 * <p>曾经设计过「只要旧的那串还没被使用就复用同一串」。核实后放弃：
 * <b>判断「旧的那串还在不在」本身就要花一次门锁云调用</b>（查密码列表，
 * 或拉开门记录去比对），与直接生成一串新的<b>成本完全相同</b> ——
 * 而复用还要多担一份「判断错了就把死码发给站在门口的人」的风险。
 * 所以：恒定一次调用、永远正确、没有缓存。
 *
 * <p>旧的那串不会因此一直留着：它是通通锁的<b>单次密码</b>（{@code keyboardPwdType=1}），
 * 要么被用掉即焚，要么 6 小时后自动失效；结算时还会再撤一次最新的一串。
 *
 * <h3>调用额度</h3>
 *
 * <p>每次调用消耗 1 次门锁云额度（每月 30,000 次的硬约束之一部分）。
 * 调用方是用户在群里的主动动作，频次天然很低 —— <b>不要在这里做任何轮询或预取</b>。
 *
 * <h3>边界</h3>
 *
 * <p>返回的 {@link OneTimePasscodeVo} 只允许被 {@code qqbot} 包拿去拼群消息，
 * <b>绝不能进任何 Controller 的返回体</b>。
 */
@Slf4j
@Service
public class OneTimePasscodeService {

    private final OrderMapper orderMapper;

    private final LockService lockService;

    public OneTimePasscodeService(OrderMapper orderMapper, LockService lockService) {
        this.orderMapper = orderMapper;
        this.lockService = lockService;
    }

    /**
     * 给这张订单取一串新的一次性密码，并记进订单（供结算时撤销）。
     *
     * @param userId  取密码的人，必须就是下单人
     * @param orderId 订单 ID
     * @return 成功时带上密码与有效期窗口；订单不存在 / 不是本人的 / 已结束 /
     *         门锁云不可用时返回对应错误码
     */
    public BizResult<OneTimePasscodeVo> issue(Long userId, Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            // 与订单模块同一条口径：不是本人的订单一律当作「不存在」，
            // 免得这个方法变成「拿别人的订单号来试」的探针
            return BizResult.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.IN_USE.name().equals(order.getStatus())) {
            return BizResult.fail(ErrorCode.ORDER_STATUS_INVALID, "这张订单已经结束了，不能再取密码");
        }
        if (order.getLockId() == null) {
            return BizResult.fail(ErrorCode.LOCK_NOT_CONFIGURED);
        }

        GetOneTimePasscodeRequest request = new GetOneTimePasscodeRequest();
        request.setLockId(order.getLockId());
        request.setKeyboardPwdName("群指令-" + order.getOrderNo());
        request.setStartTime(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));

        OneTimePasscodeResult result = lockService.getOneTimePasscode(request);
        if (!result.isSuccess()) {
            log.error("[订单] 获取一次性密码失败 orderNo={} errmsg={}",
                    order.getOrderNo(), result.getErrmsg());
            return BizResult.fail(ErrorCode.LOCK_CLOUD_UNAVAILABLE);
        }

        int updated = orderMapper.updateOneTimePasscode(
                orderId, result.getKeyboardPwd(), result.getEndTime());
        if (updated == 0) {
            // 极小的竞态窗口：取密码的这几毫秒里订单被结算了（用户同时在网页点了结账，
            // 或包场清场扫到了他）。这时【不能】把密码给出去 —— 结算后进门没有订单覆盖，
            // 是白玩。撤掉刚取的那串，按「状态不对」回话，用户看到的是「订单已结束」
            lockService.deletePasscode(order.getLockId(), result.getKeyboardPwd());
            log.warn("[订单] 取一次性密码时订单已结束，已撤销刚取的密码 orderNo={}", order.getOrderNo());
            return BizResult.fail(ErrorCode.ORDER_STATUS_INVALID, "这张订单刚刚结束了，密码没有发出");
        }

        log.info("[订单] 已发放一次性密码 orderNo={} pwdId={} 有效至 {}",
                order.getOrderNo(), result.getKeyboardPwdId(), result.getEndTime());
        return BizResult.ok(OneTimePasscodeVo.of(
                result.getKeyboardPwd(), result.getStartTime(), result.getEndTime()));
    }
}
