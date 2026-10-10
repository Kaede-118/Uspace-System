package com.kaede.uspace.order;

import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.upload.ImageStorage;
import com.kaede.uspace.order.dto.ProofImageVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentProofImageService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库</b>：落盘用真实的 {@link ImageStorage}
 *（配一个临时目录），识别用 {@link FakeOcrService} 顶替。
 *
 * <p>本类覆盖的全部是「错了不会报错」的地方：
 *
 * <ol>
 *   <li><b>识别失败不能把上传带下水</b> —— 这是本类最要紧的一条。
 *       用户刚付完钱正等着进店，识别侧的任何毛病都不该变成「上传失败，请重试」：
 *       他重试多少次都一样，而问题根本不在他那边。
 *       网络不通、配额耗尽、乃至识别实现直接抛异常，都必须照常返回那个可用的 URL</li>
 *   <li><b>校验没过的文件不该花一次识别调用</b> —— 传上来的不是图片时，
 *       连识别都不该走到。识别是按次计费的，而这类请求是免费的
 *       （一个登录用户拿非图片刷接口，每刷一次就烧一次额度）</li>
 *   <li><b>识别的输入必须是落盘之后的那张图</b> —— 传给识别服务的字节若与
 *       存下来的不是同一份，识别结果就对应不上任何东西，而两边都不会报错</li>
 * </ol>
 */
class PaymentProofImageServiceTests {

    /** 合法的 PNG 文件头。与 {@code ImageUploadServiceTests} 用的是同一组 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    /** 一段 JSP 源码 —— 冒充图片的典型载荷 */
    private static final byte[] JSP_BYTES =
            "<%@ page import=\"java.util.*\" %>".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** 上传根目录，每个用例一个临时目录 */
    @TempDir
    Path tempDir;

    /** 上传者用户 ID。冷却按人记 —— 本类用例之间共用一个即可 */
    private static final Long USER_ID = 1001L;

    private final UploadProperties uploadProperties = new UploadProperties();
    private final FakeOcrService ocrService = new FakeOcrService();
    private final PaymentProperties paymentProperties = new PaymentProperties();

    /** 可拨动的时钟，冷却用例需要让时间流逝 */
    private final MutableClock clock = new MutableClock();

    private PaymentProofImageService service;

    /**
     * 每个用例前重新装配一遍。
     *
     * <p>目录取自 {@code @TempDir}，用例之间互不影响 —— 与
     * {@code ImageUploadServiceTests} 的做法一致。
     */
    @BeforeEach
    void setUp() {
        uploadProperties.setDir(tempDir.toString());
        uploadProperties.setUrlPrefix("/uploads");
        uploadProperties.setMaxImageBytes(2 * 1024 * 1024);
        service = new PaymentProofImageService(
                new ImageStorage(uploadProperties), ocrService, paymentProperties, clock);
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("上传 → 落盘、返回路径，并带上识别结果")
    void upload_persistsAndReturnsOcrFields() {
        ocrService.willRecognize(List.of(
                "支付成功",
                "¥8.00",
                "交易单号",
                "4200001234202609301234567890"));

        BizResult<ProofImageVo> result = service.upload(pngFile(), USER_ID);

        assertTrue(result.isSuccess(), "正常图片应当上传成功");
        ProofImageVo vo = result.getData();

        assertNotNull(vo.getProofUrl(), "必须返回站内路径 —— 前端靠它提交凭证");
        assertTrue(vo.getProofUrl().startsWith("/uploads/proof/"),
                "路径前缀同时是提交时的校验依据，改这里等于让所有截图提交失败");
        assertTrue(Files.exists(onDisk(vo.getProofUrl())), "文件要真的写进了上传目录");

        assertEquals("4200001234202609301234567890", vo.getOcrPaymentNo(),
                "识别出的单号要回给前端预填 —— 用户抄错一位正是对账困难的主要来源");
        assertEquals(0, vo.getOcrAmount().compareTo(new BigDecimal("8.00")),
                "识别出的金额要回给前端核对");
        assertNotNull(vo.getOcrText(), "原文也要带上，后台复核时能看到图里到底有什么字");
    }

    @Test
    @DisplayName("上传 → 识别读到的是落盘之后那张图")
    void upload_ocrSeesTheStoredFile() throws Exception {
        service.upload(pngFile(), USER_ID);

        byte[] seen = ocrService.lastImage();
        assertNotNull(seen, "识别服务必须被调用到");
        assertArrayEquals(PNG_BYTES, seen,
                "传给识别的必须是刚落盘的那份字节。两边不一致的话，"
                        + "识别结果就对应不上任何一张图，而两边都不会报错");
    }

    @Test
    @DisplayName("识别什么都没认出 → 三个字段为空，上传照样成功")
    void upload_withoutRecognizedFields() {
        // FakeOcrService 默认就是「成功但没认出内容」，对应 mock 档下的每一次上传
        BizResult<ProofImageVo> result = service.upload(pngFile(), USER_ID);

        assertTrue(result.isSuccess(), "识别不出内容不是错误");
        assertNull(result.getData().getOcrPaymentNo(), "没认出来就该是 null，前端照常让用户手填");
        assertNull(result.getData().getOcrAmount(), "金额同理");
        assertNull(result.getData().getOcrText(), "原文同理");
    }

    // ==================================================================
    // 识别侧出问题时，上传必须活着
    // ==================================================================

    @Test
    @DisplayName("识别调用失败 → 上传照样成功，三个字段为空")
    void upload_survivesOcrFailure() {
        ocrService.willFail(17, "Open api daily request limit reached");

        BizResult<ProofImageVo> result = service.upload(pngFile(), USER_ID);

        assertTrue(result.isSuccess(),
                "配额耗尽不该让用户传不了图 —— 他手上那张截图是好的，重试多少次都一样");
        assertNotNull(result.getData().getProofUrl(), "URL 必须是可用的");
        assertNull(result.getData().getOcrPaymentNo(), "没有识别结果就是没有");
    }

    @Test
    @DisplayName("识别实现抛异常 → 上传照样成功")
    void upload_survivesOcrException() {
        // 契约上 OcrService 不该抛业务异常，但真抛了也不能把一次成功的上传带崩
        ocrService.willThrow(new IllegalStateException("模拟实现失控"));

        BizResult<ProofImageVo> result = service.upload(pngFile(), USER_ID);

        assertTrue(result.isSuccess(), "识别侧的实现问题不该由用户来承担");
        assertNotNull(result.getData().getProofUrl(), "文件已经落盘、URL 已经可用");
    }

    // ==================================================================
    // 校验拦下的请求，不该花掉一次识别
    // ==================================================================

    @Test
    @DisplayName("传上来的不是图片 → 拒绝，且根本不调识别")
    void upload_invalidFileSkipsRecognition() {
        BizResult<ProofImageVo> result = service.upload(
                new MockMultipartFile("file", "shell.png", "image/png", JSP_BYTES), USER_ID);

        assertEquals(ErrorCode.UPLOAD_FILE_INVALID, result.getError(), "按文件头判类型，不看声明的 Content-Type");
        assertEquals(0, ocrService.callCount(),
                "校验没过的文件不该走到识别 —— 识别是按次计费的，"
                        + "而这类请求是免费的，每刷一次就烧一次额度");
    }

    @Test
    @DisplayName("请求里没带 file 部分 → 拒绝，且根本不调识别")
    void upload_missingFileSkipsRecognition() {
        // ⚠️ 强转不能省：加了 upload(byte[]) 重载之后裸的 null 两个都匹配得上，
        // 编译期报「对 upload 的引用不明确」。这里要测的是网页表单那条路
        BizResult<ProofImageVo> result = service.upload((MultipartFile) null, USER_ID);

        assertEquals(ErrorCode.UPLOAD_FILE_INVALID, result.getError(),
                "没带 file 要落到 Service 的校验里变成 400，而不是抛成 500");
        assertEquals(0, ocrService.callCount(), "同上：没通过校验就不该花识别调用");
    }

    @Test
    @DisplayName("图片超过大小上限 → 拒绝，且根本不调识别")
    void upload_oversizedFileSkipsRecognition() {
        uploadProperties.setMaxImageBytes(1024);

        BizResult<ProofImageVo> result = service.upload(
                new MockMultipartFile("file", "big.png", "image/png", new byte[3 * 1024]), USER_ID);

        assertEquals(ErrorCode.UPLOAD_FILE_TOO_LARGE, result.getError(), "超限要按大小错误码返回");
        assertEquals(0, ocrService.callCount(), "同上");
    }

    // ==================================================================
    // 上传冷却（OCR 额度的闸）
    // ==================================================================

    @Test
    @DisplayName("冷却：连着传两张 → 第二张被挡，且报出还要等几秒")
    void upload_cooldownBlocksSecondUpload() {
        service.upload(pngFile(), USER_ID);

        BizResult<ProofImageVo> second = service.upload(pngFile(), USER_ID);

        assertEquals(ErrorCode.PAYMENT_PROOF_COOLDOWN, second.getError(),
                "识别在上传时做一次、按次计费 —— 冷却挡的就是连点与脚本烧额度");
        assertTrue(second.resolveMessage().contains("秒后再试"),
                "只说「太频繁」而不给时长，用户只能瞎试：" + second.resolveMessage());
    }

    @Test
    @DisplayName("冷却：等待时长过后放行 —— 它是一段时间窗，不是永久拉黑")
    void upload_cooldownReleasesAfterWait() {
        service.upload(pngFile(), USER_ID);
        clock.advance(Duration.ofSeconds(11));

        assertTrue(service.upload(pngFile(), USER_ID).isSuccess());
    }

    @Test
    @DisplayName("冷却：按用户各记各的 —— 一个人被挡不影响别人")
    void upload_cooldownIsPerUser() {
        service.upload(pngFile(), USER_ID);

        assertTrue(service.upload(pngFile(), USER_ID + 1).isSuccess(),
                "同一时刻只会有一个顾客在传自己的截图，互相之间不该牵连");
    }

    @Test
    @DisplayName("冷却：⚠️ 失败的上传不计时（挑错文件后换一张要能立刻重传）")
    void upload_failureDoesNotStartCooldown() {
        // 先传一个不是图片的文件（被校验挡下、根本没花识别）
        service.upload(new MockMultipartFile(
                "file", "shell.png", "image/png", JSP_BYTES), USER_ID);

        BizResult<ProofImageVo> retry = service.upload(pngFile(), USER_ID);

        assertTrue(retry.isSuccess(),
                "⚠️ 若失败也计时，用户挑错文件后换一张合法图立刻重传会被莫名拒绝 —— "
                        + "那不是他做错了什么，而失败的那次根本没花识别额度");
    }

    @Test
    @DisplayName("冷却：时长配成 0 表示不限制")
    void upload_cooldownCanBeDisabled() {
        paymentProperties.setProofImageCooldown(Duration.ZERO);
        service.upload(pngFile(), USER_ID);

        assertTrue(service.upload(pngFile(), USER_ID).isSuccess(), "0 = 关掉冷却");
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 可拨动的时钟 —— 冷却用例需要「时间流逝」。
     *
     * <p>项目里测固定时刻用的是 {@code Clock.fixed}，但冷却要测的是
     * 「等一会儿之后放行」，固定时钟做不到；这里给它加一个 {@link #advance}。
     */
    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-10-10T10:00:00Z");

        /**
         * 把时间往前拨。
         *
         * @param duration 拨动的时长
         */
        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * 造一个合法的上传文件。
     *
     * @return 带 PNG 文件头的 multipart 文件
     */
    private static MultipartFile pngFile() {
        return new MockMultipartFile("file", "shot.png", "image/png", PNG_BYTES);
    }

    /**
     * 把站内相对路径还原成磁盘路径。
     *
     * @param url 形如 {@code /uploads/proof/xxxx.png}
     * @return 绝对路径
     */
    private Path onDisk(String url) {
        return tempDir.resolve(url.substring("/uploads/".length()));
    }
}
