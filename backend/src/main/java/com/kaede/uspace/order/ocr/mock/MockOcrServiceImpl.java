package com.kaede.uspace.order.ocr.mock;

import com.kaede.uspace.order.ocr.OcrResult;
import com.kaede.uspace.order.ocr.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文字识别的模拟实现，对应 {@code uspace.ocr.provider=mock}（默认）。
 *
 * <p><b>它一次也不访问外网，也一次都不「识别」—— 恒返回「没认出内容」。</b>
 *
 * <h3>为什么不造一份假的识别结果</h3>
 *
 * <p>门锁的模拟实现会生成密码、支付的模拟实现会完成支付，它们都<b>产出可观察的假数据</b>，
 * 因为那些假数据在演示时正是要展示的东西。OCR 不一样：它的产物是
 * 「截图里写着 8.00 元、单号 4200…」这样一条<b>关于事实的陈述</b>。
 * 凭空造一条出来，管理员在后台看到的就是一个不存在的金额 ——
 * 他要么被误导，要么学会不信任这一栏（而后者更糟：真识别出来的时候他也不看了）。
 *
 * <p>所以模拟实现守的是一条更朴素的底线：<b>宁可不说话，也不说假话</b>。
 * 装上真引擎（{@code provider=baidu}）就能看到真结果，没装就什么都看不到 ——
 * 而「什么都看不到」恰好等于功能没上线时的状态，整条链路照常走得通。
 *
 * <h3>为什么没有失败注入</h3>
 *
 * <p>门锁与支付都留了 {@code failure-rate} 用来演示异常分支，
 * 因为那两条链路的失败<b>会改变主流程的走向</b>（下单失败、回调丢失）。
 * OCR 的失败不改变任何走向：调用方本来就按「识别不出」处理，
 * 而 {@code mock} 返回的就是「识别不出」—— 再注入一次失败，产生的是一模一样的结果，
 * 没有可观察的差异。真要演练异常分支，改 {@code provider=baidu} 并把 key 填错即可，
 * 那走的是一条真实的失败路径。
 *
 * @see com.kaede.uspace.order.ocr.baidu.BaiduOcrServiceImpl 真实实现
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "uspace.ocr", name = "provider",
        havingValue = "mock", matchIfMissing = true)
public class MockOcrServiceImpl implements OcrService {

    /** 构造时打一行日志，让「当前是哪一档」在启动日志里一眼可见 */
    public MockOcrServiceImpl() {
        log.info("[MockOCR] 模拟实现已启用（provider=mock），不会访问真实文字识别服务，"
                + "付款截图不会有识别结果");
    }

    /**
     * 不做任何识别，直接返回「什么都没认出」。
     *
     * <p>返回的是 {@code success=true} + 空列表，<b>不是失败</b>：
     * 这条路径上什么都没出错，只是没有识别能力而已。调用方对两者的处置相同
     *（都当作「没识别出」），但日志里分得清。
     *
     * @param imageBytes 图片字节，本实现不看它
     * @return 恒为「成功但无内容」
     */
    @Override
    public OcrResult recognize(byte[] imageBytes) {
        return OcrResult.ok(List.of());
    }
}
