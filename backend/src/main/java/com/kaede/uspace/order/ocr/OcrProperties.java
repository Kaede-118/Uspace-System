package com.kaede.uspace.order.ocr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文字识别配置，对应配置文件中的 {@code uspace.ocr.*}。
 *
 * <p>用途与 {@code LockProperties}、{@code PaymentProperties} 相同：
 * 在不改动任何业务代码的前提下切换识别服务的实现方式。
 *
 * @see com.kaede.uspace.order.ocr.baidu.BaiduOcrServiceImpl 真实实现，读这里的 {@link Baidu}
 */
@Data
@Component
@ConfigurationProperties(prefix = "uspace.ocr")
public class OcrProperties {

    /**
     * 实现方式，两档：
     * <ul>
     *   <li>{@code mock} —— 不识别，恒返回「未识别出」（默认）。
     *       不需要任何账号，整条链路照常走得通，只是后台的识别栏是空的</li>
     *   <li>{@code baidu} —— 真实百度智能云 OCR，需要填下面的 {@link Baidu} 凭据</li>
     * </ul>
     *
     * <p><b>只有两档，没有 {@code disabled}。</b>
     * 支付那边之所以必须有 {@code disabled} 这一档，是因为「本店没开线上支付」
     * 是一种真实的<b>业务状态</b>，它要返回一个语义明确的失败码告诉用户「走扫码转账」。
     * 而 OCR 没有对应的业务状态 —— 它不识别的时候，用户看到的本来就是
     * 「没识别出单号，请自己填」，与「识别了但图上没有」是同一件事。
     * 多一档只是多一个要维护、要解释、要写测试的取值。
     *
     * <p>⚠️ 配成这两者之外的任何值都会让容器装配失败、<b>应用直接起不来</b> ——
     * 两个实现都挂在 {@code @ConditionalOnProperty} 上，而没有第三个兜底实现。
     * 这是刻意的：{@code uspace.ocr} 不像支付那样在投产前后反复切换，
     * 一个拼错的取值就该在启动时立刻暴露。
     */
    private String provider = "mock";

    /**
     * 识别超时（毫秒），默认 3000。
     *
     * <p>调用发生在用户上传截图的等待路径上 —— 他刚选完图，正盯着进度条。
     * 识别再准也不值得让他多等十秒，所以这个值要给得短。
     *
     * <p>超时按「没识别出」处理，<b>不会让上传失败</b>：上传是主流程，
     * 识别是搭在上面的辅助。宁可这次没有识别结果，也不能让用户传不了图。
     */
    private long timeoutMillis = 3000;

    /** 百度智能云的接入参数，{@code provider=baidu} 时必填 */
    private final Baidu baidu = new Baidu();

    /**
     * 百度智能云 OCR 的凭据。
     *
     * <p>在百度智能云控制台创建「文字识别」应用后获得。<b>个人开发者即可注册</b>，
     * 通用文字识别（高精度版）提供每月 1000 次免费额度 —— 按本店一天三十来单的
     * 量算，一个月约 900 次，刚好在额度内。
     *
     * <p>⚠️ 与 {@code uspace.auth.secret} 同一条纪律：<b>凭据不写进配置文件</b>，
     * 走环境变量注入，生产环境尤其如此。这里留空是为了让"忘了配"能在启动时
     * 被立刻发现，而不是静默地永远识别不出（见 {@code BaiduOcrServiceImpl} 的构造器）。
     */
    @Data
    public static class Baidu {

        /** 应用的 API Key（百度控制台里的「API Key」，即 OAuth2 的 client_id） */
        private String apiKey;

        /** 应用的 Secret Key（即 OAuth2 的 client_secret） */
        private String secretKey;
    }
}
