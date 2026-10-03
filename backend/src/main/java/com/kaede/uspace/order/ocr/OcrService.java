package com.kaede.uspace.order.ocr;

/**
 * 文字识别服务接口（模块 8 的支付能力）。
 *
 * <p><b>本接口的方法与返回字段按百度智能云 OCR 的真实规格设计</b>，
 * 对应其「通用文字识别（高精度版）」—— 当前由 {@code mock} 子包下的模拟实现提供，
 * 将来换识别引擎时只需新增一个实现类，调用方无需改动。
 *
 * <p>与 {@code LockService}（门锁）、{@code PaymentGateway}（支付网关）是同一套思路，
 * 见 {@code docs/开发约定与设计说明.md} 第七章与第九章。
 *
 * <p>实现类的选择由配置项 {@code uspace.ocr.provider} 决定：
 * {@link #recognize} 的两档是 {@code mock}（默认，不识别）与 {@code baidu}（真实云端）。
 *
 * <h3>它在整条链路上的位置</h3>
 *
 * <p>用户上传付款截图时被调用一次，结果交给 {@link OcrTextParser} 解析出
 * 「金额」与「交易单号」，随截图一起回给用户核对。<b>只在上传时调一次</b>，
 * 提交凭证时不再识别 —— 一单两次调用会让云端额度当月翻倍耗尽
 *（通用文字识别高精度版的免费额度是 1000 次/月），而结果并不因此更准。
 *
 * <p>⚠️ <b>调用方必须把「失败」当作「没识别出」来处理，不能让异常影响主流程。</b>
 * OCR 是辅助线索：用户传图这件事不该因为云端抽风而失败。
 * 本接口的方法<b>不抛业务异常</b>，失败一律走 {@link OcrResult} 的 {@code success=false}
 * —— 理由与 {@code PaymentGateway} 相同：调用方要的是「能不能继续」，
 * 而异常会把「上游拒绝」与「我们自己有 bug」混成同一类东西，在日志里分不开。
 *
 * @see com.kaede.uspace.order.ocr.mock.MockOcrServiceImpl 默认实现：不访问外网，恒返回「未识别出」
 * @see com.kaede.uspace.order.ocr.baidu.BaiduOcrServiceImpl 真实实现：调百度智能云
 */
public interface OcrService {

    /**
     * 识别一张图片里的文字。
     *
     * <p>对应百度 OCR {@code POST /rest/2.0/ocr/v1/accurate_basic}：
     * 请求体是 form-urlencoded，图片以 Base64 放在 {@code image} 参数里，
     * 返回 {@code words_result} 数组，每项一个 {@code words} 字段。
     *
     * <p><b>入参是字节而不是文件或路径</b>：实现不该关心图从哪来（上传流、磁盘文件、
     * 将来可能的重新识别），那样它才能脱离 Web 层被单测。
     *
     * <p>⚠️ <b>本方法可能耗时到 {@code uspace.ocr.timeout-millis} 那么久</b>，
     * 调用方处在用户的等待路径上（上传接口），超时要设得短。
     *
     * @param imageBytes 图片的原始字节。调用方须先保证它不是空数组
     * @return 识别结果。{@code success=false} 表示这次调用没成，
     *         与「图里没有可用文字」（{@code success=true} 但 lines 为空）是两回事
     */
    OcrResult recognize(byte[] imageBytes);
}
