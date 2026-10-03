package com.kaede.uspace.order;

import com.kaede.uspace.order.ocr.OcrResult;
import com.kaede.uspace.order.ocr.OcrService;

import java.util.List;

/**
 * 内存版的 {@link OcrService}，让付款截图上传的单元测试不依赖识别服务。
 *
 * <p>写法与 {@code FakePaymentGateway} 一致：<b>直接实现接口的手写类</b>，
 * 而不是 Mockito 那种运行期生成的替身 —— 全项目的单测都是这个路子，
 * 因为手写的替身能被读到、能读懂，答辩时也讲得清。
 *
 * <p>它能构造出四种情形，正好覆盖上传链路要考虑的全部：<b>认出东西</b>、
 * <b>什么都没认出</b>、<b>调用失败</b>（网络、配额）、
 * <b>实现抛异常</b>（违反 {@link OcrService} 契约时调用方扛不扛得住）。
 * 后两种在真实世界里都会发生，而它们<b>都不该影响上传本身</b>。
 */
public class FakeOcrService implements OcrService {

    /** 下次识别返回的文本行，默认空（表示「没认出内容」） */
    private List<String> lines = List.of();

    /** 非空时表示「这次调用失败了」，优先于 {@link #lines} */
    private OcrResult failure;

    /** 非空时表示「实现抛异常」。用来验证调用方扛不扛得住最坏情况 */
    private RuntimeException thrown;

    /** 被调用了几次。用于断言「校验没过的文件不该花这一次调用」 */
    private int callCount;

    /** 最后一次收到的图片字节。用于断言调用方传的是落盘之后的那张图 */
    private byte[] lastImage;

    /**
     * 识别。
     *
     * @param imageBytes 图片字节
     * @return 由 {@link #willRecognize} / {@link #willFail} 设定的结果
     * @throws RuntimeException 设过 {@link #willThrow} 时抛出
     */
    @Override
    public OcrResult recognize(byte[] imageBytes) {
        callCount++;
        lastImage = imageBytes;
        if (thrown != null) {
            throw thrown;
        }
        if (failure != null) {
            return failure;
        }
        return OcrResult.ok(lines);
    }

    // ==================================================================
    // 以下为测试专有的注入与观察方法，不属于 OcrService 接口
    // ==================================================================

    /**
     * 让下次识别返回这些文本行。
     *
     * @param lines 文本行，会被调用方交给 {@code OcrTextParser} 解析
     */
    public void willRecognize(List<String> lines) {
        this.lines = lines;
        this.failure = null;
        this.thrown = null;
    }

    /**
     * 让下次识别失败。
     *
     * @param errcode 外部错误码，可为 null
     * @param errmsg  错误描述
     */
    public void willFail(Integer errcode, String errmsg) {
        this.failure = OcrResult.fail(errcode, errmsg);
        this.thrown = null;
    }

    /**
     * 让下次识别抛异常。
     *
     * <p>这是 {@link OcrService} 契约之外的行为（接口明写了不抛业务异常），
     * 但调用方必须扛得住 —— 用户刚付完钱，不该因为识别服务的实现问题传不了图。
     *
     * @param e 要抛的异常
     */
    public void willThrow(RuntimeException e) {
        this.thrown = e;
        this.failure = null;
    }

    /**
     * 识别被调用了几次。
     *
     * @return 调用次数
     */
    public int callCount() {
        return callCount;
    }

    /**
     * 最后一次被识别的那张图的字节。
     *
     * @return 图片字节；一次都没调用过时返回 null
     */
    public byte[] lastImage() {
        return lastImage;
    }
}
