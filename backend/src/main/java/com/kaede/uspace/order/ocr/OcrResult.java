package com.kaede.uspace.order.ocr;

import lombok.Data;

import java.util.List;

/**
 * OCR 识别的调用结果。
 *
 * <p>结构对齐百度 OCR 的返回体（成功时是 {@code {"words_result": [...], "log_id": ...}}，
 * 失败时是 {@code {"error_code": 17, "error_msg": "Open api daily request limit reached"}}），
 * 便于真实实现直接映射 —— 与 {@code PasscodeResult} 对齐通通锁是同一种做法。
 *
 * <p><b>「调用失败」与「一个字都没认出」是两回事</b>，本类用 {@link #success} 区分：
 * <ul>
 *   <li>{@code success=true} + {@link #lines} 为空 —— 图能读，但里面没有可用的文字。
 *       用户传了一张风景照就会走到这里</li>
 *   <li>{@code success=false} —— 这次调用本身没成（网络、鉴权、配额）。
 *       {@link #errcode} 与 {@link #errmsg} 是百度给的原因，用于日志定位</li>
 * </ul>
 *
 * <p>⚠️ <b>两种情形在上层是同一个处置：当作「识别不出」。</b>
 * 见 {@code PaymentProofImageService} —— OCR 是辅助，它的失败不该让用户传不了图。
 * 分开表达是为了<b>日志</b>：配额耗尽与「这张图没字」在排查时是完全不同的两件事。
 *
 * <p>使用建议与 {@code PasscodeResult} 一致：判断成败请用 {@link #isSuccess()}，
 * {@link #getErrcode()} 与 {@link #getErrmsg()} 用于日志记录与问题定位。
 */
@Data
public class OcrResult {

    /** 本次调用是否成功。失败的原因见 {@link #errcode} */
    private boolean success;

    /**
     * 百度 OCR 的错误码，成功时为 0。
     *
     * <p>常见取值：{@code 110} token 失效、{@code 111} token 过期、
     * {@code 17} 日调用量超限、{@code 18} QPS 超限、{@code 216630} 识别错误。
     * 完整表见百度智能云文档 —— <b>这些编码不写进本项目的 {@code ErrorCode}</b>，
     * 它们只出现在日志里，用户看到的永远是「没识别出」这一件事。
     */
    private Integer errcode;

    /** 错误描述，用于日志与问题定位 */
    private String errmsg;

    /**
     * 识别出的文本行，按图片中自上而下的顺序。
     *
     * <p>刻意只取「一行文字」而不带坐标：本项目要的是金额与单号这两个字段，
     * 靠文本内容就能定位（见 {@link OcrTextParser}），
     * 而带位置的接口版本（{@code accurate}）单次价格更高。
     */
    private List<String> lines;

    /**
     * 构造一个成功的结果。
     *
     * @param lines 识别出的文本行，可为空列表（表示图里没有可用文字）
     * @return 成功结果
     */
    public static OcrResult ok(List<String> lines) {
        OcrResult result = new OcrResult();
        result.setSuccess(true);
        result.setErrcode(0);
        result.setErrmsg("success");
        result.setLines(lines == null ? List.of() : lines);
        return result;
    }

    /**
     * 构造一个失败的结果。
     *
     * @param errcode 百度错误码，可为 null（本地异常时为 null）
     * @param errmsg  错误描述
     * @return 失败结果
     */
    public static OcrResult fail(Integer errcode, String errmsg) {
        OcrResult result = new OcrResult();
        result.setSuccess(false);
        result.setErrcode(errcode);
        result.setErrmsg(errmsg);
        result.setLines(List.of());
        return result;
    }
}
