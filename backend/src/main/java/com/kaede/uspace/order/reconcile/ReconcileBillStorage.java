package com.kaede.uspace.order.reconcile;

import com.kaede.uspace.common.config.UploadProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 账单原文件的留档与读取。
 *
 * <h3>为什么不复用公共层的 {@code ImageStorage}</h3>
 *
 * <p>那个类的第一步是<b>按文件头判图片类型</b>，而账单是 CSV / xlsx，过不了那一关；
 * 而且它的目录是公开映射的。</p>
 *
 * <p>两者共有的只是「写文件」这一句，为它去改一个被三处使用的安全组件的签名，
 * 代价远大于各写一份 —— 这里没有任何一行是<em>安全代码的复制</em>：
 * 类型判定由 {@code BillRowReaders} 负责，本类只管落盘与路径校验。
 *
 * <h3>目录绝不能落在上传目录之下</h3>
 *
 * <p>{@code /uploads/**} 在 {@code SecurityConfig.PUBLIC_PATHS} 里是<b>匿名可访问</b>的
 *（用户上传的头像与截图要用 {@code <img src>} 加载，浏览器不为图片请求带
 * {@code Authorization} 头）。账单里含全部交易对手、备注、金额与时间 ——
 * 落在那里等于挂在公网上。
 *
 * <p>所以本类在启动时比较两个目录，重叠就<b>抛异常拒绝启动</b>。
 * 这是刻意的 fail-fast，与「{@code JWT_SECRET} 未设置拒绝启动」同源：
 * 配置写错不会有任何报错，只会悄悄把本店最敏感的数据公开出去，
 * 而这种事没有任何人会注意到。
 *
 * <h3>落盘失败不阻断对账</h3>
 *
 * <p>{@link #store} 失败时记 warn 并返回 null，<b>不抛异常</b>。
 * 管理员的核心目的是把账对起来，留档只是「事后能翻回来看看当初传的是什么」——
 * 为了次要目标让主要目标失败是本末倒置。这与
 * {@code PaymentProofImageService} 里「OCR 识别不能拖垮上传」是同一条取向。
 *
 * <p>代价是 {@code bill_file_path} 为空，下载接口据此返回
 * {@code RECONCILE_BILL_FILE_MISSING}，页面上把下载按钮置灰。
 */
@Slf4j
@Component
public class ReconcileBillStorage {

    /** 账单留档目录的绝对规范路径 */
    private final Path billDir;

    /** 公开上传目录的绝对规范路径，只用来做重叠校验 */
    private final Path uploadDir;

    /**
     * 构造器注入。
     *
     * <p>两个目录都在这里 {@code normalize()} 成绝对路径：{@code uspace.upload.dir}
     * 与 {@code uspace.reconcile.bill-dir} 的默认值都是相对路径（相对进程工作目录），
     * 而「A 是不是在 B 下面」这个判断只有在两者都规范化之后才有意义 ——
     * {@code uploads} 与 {@code ./uploads/} 是同一个目录，但字符串比较看不出来。
     *
     * @param properties       对账配置，提供留档目录
     * @param uploadProperties 上传配置，提供公开目录（只需它的路径，不读写它）
     */
    public ReconcileBillStorage(ReconcileProperties properties, UploadProperties uploadProperties) {
        this.billDir = Paths.get(properties.getBillDir()).toAbsolutePath().normalize();
        this.uploadDir = Paths.get(uploadProperties.getDir()).toAbsolutePath().normalize();
    }

    /**
     * 启动时校验留档目录不在公开的上传目录之下。
     *
     * @throws IllegalStateException 两个目录重叠时
     */
    @PostConstruct
    void validateLocation() {
        if (billDir.startsWith(uploadDir)) {
            throw new IllegalStateException(
                    "对账账单目录不能落在上传目录之下：bill-dir=" + billDir + " 在 upload.dir=" + uploadDir
                            + " 里面。上传目录是公开可访问的（/uploads/** 在 PUBLIC_PATHS 里），"
                            + "而账单含全部交易对手与金额 —— 放进去等于把它们挂在公网上。"
                            + "请把 uspace.reconcile.bill-dir 改到 upload.dir 之外。");
        }
    }

    /**
     * 落盘留档。
     *
     * <p>文件名用 UUID，<b>不采信上传时的文件名</b>，只从它那里取一个清洗过的扩展名。
     * 理由与商品封面同一套：管理员给的文件名是自由文本（含空格、斜杠、
     * Windows 下还有 {@code CON} / {@code PRN} 这类保留名），
     * 想清洗成安全文件名的那套规则没有任何测试能穷尽。
     *
     * <p>顺带买到一件事：UUID 不会重名，所以不存在「重传覆盖同一路径」，
     * 也就没有「写完新文件删旧文件」那套判断。
     *
     * @param bytes        文件内容
     * @param originalName 上传时的原始文件名，只用来取扩展名
     * @return 落盘的相对路径（相对留档目录），供写进 {@code bill_file_path}；
     *         <b>失败时返回 null</b>，调用方据此把批次上的这一列留空
     */
    public String store(byte[] bytes, String originalName) {
        String fileName = UUID.randomUUID() + safeExtension(originalName);
        try {
            Files.createDirectories(billDir);
            Files.write(billDir.resolve(fileName), bytes);
            return fileName;
        } catch (IOException e) {
            log.warn("[对账] ⚠️ 账单原文件留档失败，本次对账继续（对账结果不受影响）file={}",
                    originalName, e);
            return null;
        }
    }

    /**
     * 读回留档的账单文件。
     *
     * @param relativePath {@code bill_file_path} 里存的相对路径，可为 null
     * @return 文件内容；路径为空、越界或文件已不在时返回 null
     */
    public byte[] load(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path target = billDir.resolve(relativePath).normalize();
        // 路径越界防御：只比字符串开头的话 /uploads/../etc/passwd 能通过检查。
        // 这一列的值是服务端自己写进去的，理论上不会越界 —— 但校验的代价是两行，
        // 而漏掉的后果是「读走服务器上的任意文件」。与 ImageStorage 同一条纪律
        if (!target.startsWith(billDir)) {
            log.warn("[对账] ⚠️ 账单文件路径越界，拒绝读取 path={}", relativePath);
            return null;
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            log.warn("[对账] 账单原文件读不出来（可能已被清理）path={}", relativePath, e);
            return null;
        }
    }

    /**
     * 从原始文件名里取一个安全的扩展名。
     *
     * <p>三点限制：只取最后一段点之后的部分、只允许字母数字、
     * 长度不超过 8。任一不满足就干脆不要扩展名 ——
     * 扩展名只影响下载时的观感，而为了它放一个可以带斜杠或空格的字符串进来是不划算的。
     *
     * <p>顺带一提，它<b>不参与任何类型判定</b>：文件该用哪个读取器是由文件头决定的
     *（见 {@code BillRowReaders}）。扩展名在这里纯属展示。
     *
     * @param originalName 原始文件名，可为 null
     * @return 形如 {@code .csv} 的扩展名；取不到时返回空串
     */
    private static String safeExtension(String originalName) {
        if (originalName == null) {
            return "";
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return "";
        }
        String extension = originalName.substring(dot + 1);
        if (extension.length() > 8 || !extension.matches("[A-Za-z0-9]+")) {
            return "";
        }
        return "." + extension.toLowerCase();
    }
}
