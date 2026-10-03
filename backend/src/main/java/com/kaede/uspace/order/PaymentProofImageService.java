package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.upload.ImageStorage;
import com.kaede.uspace.common.upload.StoredImage;
import com.kaede.uspace.order.dto.ProofImageVo;
import com.kaede.uspace.order.ocr.OcrFields;
import com.kaede.uspace.order.ocr.OcrResult;
import com.kaede.uspace.order.ocr.OcrService;
import com.kaede.uspace.order.ocr.OcrTextParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 付款截图的上传与识别（模块 8 的支付能力）。
 *
 * <p><b>只把文件存下来、返回站内路径，不碰数据库</b> —— 与商品封面
 *（{@code ProductCoverService}）、收款码图片（{@code PayQrImageService}）
 * 是同一套语义，三条理由也一样：新增时还没有记录可写、表单是
 *「填一半可以取消」的、传错可以反复换一张。
 *
 * <p>对付款凭证而言还多一条：用户传完图要核对流水号、可能还要改一遍再提交。
 * 上传那一刻就落库，等于把「还没确认的东西」记成了一条凭证。
 *
 * <p>⚠️ <b>本类读两个依赖，却仍然没有 Mapper</b>：{@link ImageStorage} 只把文件写进磁盘，
 * {@link OcrService} 只读图片、不认识任何一张表。<b>「上传不写库」这条语义的结构性证明
 * 落在「没有 Mapper」上，而不是「依赖的个数」上</b> —— 2026-09-30 加识别时正是按这条
 * 判断的：多一个只读依赖不动摇它，哪天这里出现第一个 Mapper，那条语义就破了。
 *
 * <p><b>文件名用 UUID</b>：付款截图是用户在微信 / 支付宝里随手截的，
 * 文件名千奇百怪（含空格、中文、emoji），清洗成安全文件名的那套规则
 * 没有测试能穷尽。UUID 顺带买到：不会重名（所以不存在「重传覆盖同一路径」，
 * 也就没有删旧图那套判断），以及 URL 每次都变、前端不需要防缓存。
 *
 * <p><b>关于格式</b>：{@link ImageStorage#store} 按文件头只认 JPEG / PNG / WebP。
 * 本条链路不做任何转码，原样落盘 —— 转码发生在浏览器端
 *（{@code utils/image.js} 的 {@code proof} 处理器：不裁剪、长边 1600、
 * JPEG 质量 0.92），因为 OCR 认不认得出流水号，直接取决于这一步的参数。
 *
 * <p><b>孤儿文件的代价同样存在</b>：传了图又不提交，文件就留在盘上没人引用。
 * 它只占磁盘、不影响功能，是「上传不写库」的固有结果而非 bug ——
 * 与商品封面那条注释同理。
 */
@Slf4j
@Service
public class PaymentProofImageService {

    /**
     * 付款截图的子目录名，同时也是落库路径里的一段。
     *
     * <p>包级可见：{@code PaymentProofService} 校验 {@code proofUrl} 前缀时
     * 要用同一个值。两处各写一份字符串的话，改了一处而另一处没改，结果是
     * <b>上传成功但提交失败</b>，报的还是「图片路径不合法」这种
     * 让人摸不着头脑的话 —— 与 {@code PayQrImageService} 上那条警告同源。
     */
    static final String KIND_PROOF = "proof";

    private final ImageStorage imageStorage;
    private final OcrService ocrService;

    /**
     * 构造器注入。
     *
     * @param imageStorage 公共层的图片落盘组件（与头像 / 背景图 / 商品封面 /
     *                     收款码共用同一份实现 —— 文件头判类型、路径越界防御
     *                     那些安全代码不能有第二份）
     * @param ocrService   文字识别服务，落盘后调一次。它永远不写库，
     *                     也永远不抛异常（见 {@link #recognizeQuietly}）
     */
    public PaymentProofImageService(ImageStorage imageStorage, OcrService ocrService) {
        this.imageStorage = imageStorage;
        this.ocrService = ocrService;
    }

    /**
     * 上传一张付款截图，顺带识别一遍。
     *
     * <p>顺序是<b>先落盘、再识别</b>：校验没过的文件（不是图片、超过大小上限）
     * 根本不该花那一次识别调用，而识别要读的字节也正是落盘之后才稳定下来的。
     *
     * @param file 上传的图片，可为 null（表示请求里没带 file 部分）
     * @return 成功时返回站内路径与识别结果；校验不过时返回对应错误码
     */
    public BizResult<ProofImageVo> upload(MultipartFile file) {
        return store(imageStorage.store(KIND_PROOF, newBaseName(), file));
    }

    /**
     * 用字节数组上传一张付款截图 —— 图不是来自网页表单时走这里。
     *
     * <p>目前唯一的调用方是<b>群里的付款截图</b>（模块 11）：用户在群里发的图
     * 是一串字节（从 NapCat 给的地址下回来的），没有 {@code MultipartFile}
     * 这层载体。识别与落盘逻辑与网页那版<b>完全共用</b>，两条路不会分岔。
     *
     * @param bytes 图片字节
     * @return 同 {@link #upload(MultipartFile)}
     */
    public BizResult<ProofImageVo> upload(byte[] bytes) {
        return store(imageStorage.store(KIND_PROOF, newBaseName(), bytes));
    }

    /** @return 一个随机文件名主干（不带扩展名，扩展名由文件头决定） */
    private static String newBaseName() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 落盘结果 → 返回体（两个上传入口共用的收尾）。
     *
     * @param stored 落盘结果
     * @return 成功时带上站内路径与识别结果；失败时把错误码与附加文案一起透传
     */
    private BizResult<ProofImageVo> store(BizResult<StoredImage> stored) {
        if (!stored.isSuccess()) {
            // 连附加文案一起透传（「请选择要上传的图片」那句提示要靠它才留得住）
            return BizResult.fail(stored.getError(), stored.getMessage());
        }

        String url = stored.getData().url();
        // ⚠️ 只记路径，不记别的 —— 截图内容里有付款人昵称等个人信息，
        // 日志里出现它就等于把它散到了日志系统里
        log.info("[凭证] 付款截图已上传 url={}", url);

        return BizResult.ok(ProofImageVo.of(url, recognizeQuietly(stored.getData().path())));
    }

    /**
     * 识别截图里的金额与交易单号，<b>任何问题都当作「没识别出」</b>。
     *
     * <p>这是本类最要紧的一条纪律：<b>识别是搭在上传上的辅助，不能让上传因它失败</b>。
     * 用户刚付完钱、正等着进店，此刻任何一种识别侧的毛病都不该变成
     * 「上传失败，请重试」—— 他重试多少次都一样，而问题根本不在他那边。
     *
     * <p>因此这里连 {@link IOException} 都吞掉了，这与 {@code ImageStorage}
     * 里「磁盘写不进去要冒泡」的处置<b>正好相反</b>，两者并不矛盾：
     * 那里失败的是主流程（文件没存成，URL 是假的），
     * 而这里读的是一个<b>已经写好、URL 已经可用</b>的文件 ——
     * 读不到顶多是没有识别结果，上传本身是成功的。
     *
     * <p>⚠️ <b>日志里只出现「读到没读到」，绝不出现识别内容</b>：
     * 见 {@code PaymentProof#ocrText} 上那条说明。
     *
     * @param path 刚落盘的截图文件
     * @return 识别结果；任何异常路径上都返回 {@link OcrFields#EMPTY}
     */
    private OcrFields recognizeQuietly(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            OcrResult result = ocrService.recognize(bytes);
            if (!result.isSuccess()) {
                // 这条是真正需要人看的：网络不通、配额耗尽、凭据失效都会落到这里。
                // 「识别不出」本身不记日志 —— 那是常态（mock 档下每次都是），
                // 记下来只会把上面这类真问题淹掉
                log.warn("[凭证] 截图识别失败，按「未识别出」处理 文件={} errcode={} errmsg={}",
                        path.getFileName(), result.getErrcode(), result.getErrmsg());
                return OcrFields.EMPTY;
            }
            return OcrTextParser.parse(result.getLines());
        } catch (IOException e) {
            log.warn("[凭证] 读取截图文件失败，跳过识别（上传本身已成功）：{}", path, e);
            return OcrFields.EMPTY;
        } catch (RuntimeException e) {
            // 识别服务理应不抛异常（契约写在 OcrService 上），但真抛了也不能
            // 把一次成功的上传带崩 —— 用户手上的 URL 是好的，他没做错任何事
            log.warn("[凭证] 识别截图时出现异常，跳过识别（上传本身已成功）：{}", path, e);
            return OcrFields.EMPTY;
        }
    }
}
