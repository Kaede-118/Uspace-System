package com.kaede.uspace.common.upload;

import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 图片落盘（公共层）。
 *
 * <p>本类只管「文件」这一层：<b>校验 → 判类型 → 写进上传目录</b>，
 * 外加一个按路径删除的方法。它<b>不碰数据库，也不主动删任何旧文件</b> ——
 * 「什么时候删旧图」「写进哪一列」由调用方定，因为那两件事逐调用方不同。
 *
 * <p><b>为什么住在公共层</b>：模块 1（用户头像 / 背景图）与商品包（商品封面）
 * 都要用同一套落盘逻辑，而 {@code product} 包的规矩是「只依赖 common，
 * 不 import 任何其他业务包」—— 放在 {@code user} 包里，商品包就只能复制一份。
 * 而这里面有一段是<b>安全代码</b>（文件头判类型、路径越界防御），
 * 复制第二份意味着将来修一处漏一处。
 *
 * <p>四条实现纪律，下面各处逐条落实：
 * <ol>
 *   <li><b>按文件头判类型，不看 {@code Content-Type}</b> —— 后者是客户端说的，能随便伪造</li>
 *   <li><b>扩展名由文件头推出，调用方连指定它的机会都没有</b> ——
 *       {@link #store} 收的是不含扩展名的 {@code baseName}，所以 {@code .jsp}
 *       不可能混进文件名</li>
 *   <li><b>删文件之前先校验路径前缀</b>，且必须 {@code normalize()} 之后再比 ——
 *       少了这道，一次换图就能删掉任意文件</li>
 *   <li><b>删文件失败不阻断主流程</b> —— 换图这件事已经完成了</li>
 * </ol>
 *
 * @see com.kaede.uspace.common.config.UploadProperties 目录与大小上限的配置来源
 * @see com.kaede.uspace.common.config.WebMvcConfig 让这些文件能被 URL 访问到的映射
 */
@Slf4j
@Component
public class ImageStorage {

    private final UploadProperties uploadProperties;

    /**
     * 构造器注入。
     *
     * <p>持有 {@code uploadProperties} 的<b>引用</b>而不是快照字段值：配置改了
     * （测试里就有「构造完再改大小上限」的写法）本类要立刻看见。
     * 快照还有个更隐蔽的代价 —— 目录快照会让本类与 {@code WebMvcConfig}
     * 看到的目录分岔，而那种事**不会报错**，只是文件写到一个地方、URL 指向另一个地方。
     *
     * @param uploadProperties 上传目录、URL 前缀与大小上限
     */
    public ImageStorage(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
        log.info("[上传] 图片存储已就绪，上传目录={}", uploadProperties.getDir());
    }

    // ==================================================================
    // 落盘
    // ==================================================================

    /**
     * 校验、判类型、落盘。
     *
     * <p>文件名 = {@code baseName + "." + 由文件头推出的扩展名}，
     * 落在上传根目录的 {@code kind} 子目录下。
     *
     * <p><b>为什么子目录名与主名都由调用方给</b>：这两件事的正确答案逐调用方不同
     * （头像用 {@code {用户名}_{ID}} 是因为用户名被注册校验限死在
     * {@code [a-zA-Z0-9_]{3,20}}；商品名是中文字由文本，根本不能进文件名），
     * 属于业务决定。本类一个都不猜，但也因此必须守住那条纪律：
     * 调用方<b>没法</b>指定扩展名。
     *
     * @param kind     子目录名，如 {@code avatar} / {@code banner} / {@code product}。
     *                 它同时是落库路径里的一段，改它会让历史图片路径失效
     * @param baseName 文件名主干，<b>不含扩展名</b>。不得含有路径分隔符
     * @param file     上传的文件，可为 null（表示请求里压根没带 file 部分）
     * @return 成功时返回落盘结果（URL + 磁盘路径）；校验不过时返回对应错误码
     */
    public BizResult<StoredImage> store(String kind, String baseName, MultipartFile file) {
        // 两个路径片段是我们自己拼进 target 的，理论上调用方不会传脏值。
        // 但这条检查是 O(1) 的，而漏掉的代价是「写到另一个子目录里去」——
        // 它仍在根目录内，normalize 那道防线看不见它，所以只能在这里挡
        requirePathSegment(kind, "kind");
        requirePathSegment(baseName, "baseName");

        // file 为 null 是「请求里压根没带 file 部分」，Controller 把该参数声明为可选
        // 就是为了让它落到这里变成 400，而不是抛一个未处理的 Servlet 异常变成 500
        if (file == null || file.isEmpty()) {
            return BizResult.fail(ErrorCode.UPLOAD_FILE_INVALID, "请选择要上传的图片");
        }
        if (file.getSize() > uploadProperties.getMaxImageBytes()) {
            return BizResult.fail(ErrorCode.UPLOAD_FILE_TOO_LARGE);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传流失败", e);
        }

        ImageType type = ImageType.detect(bytes);
        if (type == null) {
            // 注意：这里不把「实际检测到什么」告诉前端。
            // 判断依据是文件内容，返回细节等于给探测者一个反馈回路。
            return BizResult.fail(ErrorCode.UPLOAD_FILE_INVALID);
        }

        Path root = uploadRoot();
        String fileName = baseName + "." + type.extension();
        Path target = root.resolve(kind + "/" + fileName).normalize();

        // 防御性检查：路径由服务端拼装，理论上不可能越界。
        // 但它是 O(1) 的，而漏掉的代价是「写文件到任意位置」，值得留着。
        if (!target.startsWith(root)) {
            throw new IllegalStateException("生成的上传路径越出上传目录：" + target);
        }

        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            // 磁盘写不进去是本服务自己的故障（目录只读、盘满、权限不对），
            // 不是用户操作不当 —— 让它冒泡到全局异常处理器，以 error 级别连堆栈一起记下来。
            // 不在这里返回 BizResult：那会让它看起来像一个正常的分支。
            throw new UncheckedIOException("写入上传文件失败：" + target, e);
        }

        String url = uploadProperties.getUrlPrefix() + "/" + kind + "/" + fileName;
        return BizResult.ok(new StoredImage(url, target));
    }

    // ==================================================================
    // 删除
    // ==================================================================

    /**
     * 按站内相对路径删除一张图。
     *
     * <p>⚠️ <b>删之前必须校验路径前缀</b>：只删 {@code uspace.upload.dir} 之下的文件。
     * 少了这道校验，一旦库里那一列被改成 {@code /etc/passwd}
     * 或 {@code ../../conf/server.xml}，一次换图就能删掉任意文件 —— 而且不报错。
     * 校验必须用 {@code Path.normalize()} 之后再比前缀，不能只比字符串开头
     * （{@code /uploads/../etc/passwd} 是能通过字符串前缀检查的）。
     *
     * <p>删除失败只记日志，不影响调用方的主流程 —— 图已经换好了，
     * 旧文件没删掉是运维问题，不该让用户看到报错。
     *
     * <p>⚠️ <b>调用方注意</b>：如果新旧两张图落在<b>同一个路径</b>上
     * （文件名固定时重传同一种格式就是这种情况），这个方法是不能调的 ——
     * 它删掉的正是刚写进去的那张，结果是新旧图都没了、而接口返回成功。
     * 那条判断属于调用方（见 {@code ImageUploadService}），不是本类的职责。
     *
     * <p>与本项目全局规矩的关系：全局约定是「未经许可不删文件、删除走回收站」。
     * 这里删的是<b>应用自己产生的运行期上传文件</b>（不是源码、不是文档），
     * 且用户已明确指示自动删。仍保留两道保险：路径前缀校验（防越界删除）
     * 与失败不阻断（防误伤主流程）。
     *
     * @param url 图片的站内相对路径，可为 null（该对象还没传过图）
     */
    public void deleteByUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String prefix = uploadProperties.getUrlPrefix();

        // 不以前缀开头的一律不碰。合法值只会是本服务自己写进去的那种路径，
        // 出现别的取值说明这条记录被人工改过 —— 那不是我们该删的东西
        if (!url.startsWith(prefix + "/")) {
            log.warn("[上传] 图片路径不在上传前缀 {} 之下，拒绝删除：{}", prefix, url);
            return;
        }

        Path root = uploadRoot();
        Path target = root.resolve(url.substring(prefix.length() + 1)).normalize();

        // ⚠️ 必须 normalize 之后再比 —— "/uploads/../etc/passwd" 能通过上面那道字符串检查
        if (!target.startsWith(root)) {
            log.warn("[上传] 图片路径越出上传目录，拒绝删除：{} —— 已归一化为 {}", url, target);
            return;
        }

        deleteQuietly(target, "删除被替换的旧图");
    }

    /**
     * 尽力删除一个文件，失败只记日志。
     *
     * <p>两个调用场景：清理被替换的旧图（见 {@link #deleteByUrl}），
     * 以及调用方在写库失败时回滚刚落盘的新图（那里拿到的是
     * {@link StoredImage#path()}，比走 URL 更直接）。两处的共同点是
     * 「删不掉也不该让用户看到报错」，所以合成一个方法。
     *
     * @param path   要删的文件
     * @param reason 记日志时说明这是什么场景下的删除
     */
    public void deleteQuietly(Path path, String reason) {
        try {
            boolean deleted = Files.deleteIfExists(path);
            if (!deleted) {
                log.debug("[上传] {}：文件本就不存在 {}", reason, path);
            }
        } catch (IOException e) {
            log.warn("[上传] {} 失败，不影响主流程：{}", reason, path, e);
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 取上传根目录的绝对路径（已归一化）。
     *
     * @return 上传根目录
     */
    private Path uploadRoot() {
        return Paths.get(uploadProperties.getDir()).toAbsolutePath().normalize();
    }

    /**
     * 校验一个将要拼进路径的片段。
     *
     * <p>挡的是「片段里带着 {@code /} 或 {@code ..} 从而改变落点」的情况 ——
     * 例如 {@code kind = "avatar/../../etc"}。那种路径<b>仍在根目录内</b>时
     * {@code normalize} + {@code startsWith} 那道防线是看不见的。
     *
     * <p>现有调用方传的都是常量与 UUID / 受校验的用户名，本方法<b>永远不会触发</b>。
     *
     * @param value 待校验的片段
     * @param name  参数名，用于异常消息
     * @throws IllegalArgumentException 片段为空、或含有路径分隔符 / {@code ..}
     */
    private static void requirePathSegment(String value, String name) {
        if (value == null || value.isBlank()
                || value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException(
                    name + " 不能为空，也不能含有路径分隔符或 ..：" + value);
        }
    }

    // ==================================================================
    // 图片类型判定
    // ==================================================================

    /**
     * 受支持的图片类型，以及由它推出的文件扩展名。
     *
     * <p><b>为什么用枚举而不是从原始文件名里截扩展名</b>：
     * 用户上传 {@code evil.jsp} 并把 {@code Content-Type} 伪装成图片时，
     * 截取文件名会原样得到 {@code .jsp}，而扩展名决定了服务器将来怎么解释这个文件。
     * 从 MIME 白名单映射则只可能产出这三个值。
     *
     * <p><b>为什么还要看文件头而不是只看扩展名或 {@code Content-Type}</b>：
     * 这两个都是客户端提供的。文件头是文件内容本身，
     * 要与一张合法图片的前若干字节完全一致才算数。
     *
     * <p>刻意保持 {@code private}：调用方连它的名字都说不出来，
     * 也就不可能绕过它去指定扩展名。
     */
    private enum ImageType {

        /** JPEG：文件头 {@code FF D8 FF} */
        JPEG("jpg", new int[]{0xFF, 0xD8, 0xFF}),

        /** PNG：文件头 {@code 89 50 4E 47 0D 0A 1A 0A} */
        PNG("png", new int[]{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}),

        /** WebP：{@code RIFF} 开头、偏移 8 处是 {@code WEBP}，单独判定，见 {@link #detect} */
        WEBP("webp", new int[]{'R', 'I', 'F', 'F'});

        /** 落盘时使用的扩展名 */
        private final String extension;

        /** 文件开头的字节序列 */
        private final int[] magic;

        ImageType(String extension, int[] magic) {
            this.extension = extension;
            this.magic = magic;
        }

        String extension() {
            return extension;
        }

        /**
         * 按文件头判定图片类型。
         *
         * <p>判定顺序上 JPEG 在最前：它的文件头最短也最常见的，
         * 放前面可以少走两次比较。三个分支互斥，顺序不影响结果。
         *
         * @param bytes 文件内容，可为任意长度
         * @return 识别出的类型；不是受支持的图片时返回 {@code null}
         */
        private static ImageType detect(byte[] bytes) {
            if (bytes == null || bytes.length < 12) {
                // 最短的判定也需要 12 字节（WebP）。比这还短的必然不是图片，
                // 提前返回也避免了下面各处对下标的边界判断
                return null;
            }
            if (startsWith(bytes, JPEG.magic)) {
                return JPEG;
            }
            if (startsWith(bytes, PNG.magic)) {
                return PNG;
            }
            // WebP 的容器格式是 RIFF，但 RIFF 也用于 wav、avi 等；
            // 偏移 8 处的 "WEBP" 才是它区别于其他 RIFF 容器的标志
            if (startsWith(bytes, WEBP.magic)
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
                return WEBP;
            }
            return null;
        }

        /**
         * 判断字节数组是否以给定的字节序列开头。
         *
         * @param data   被检查的内容
         * @param prefix 期望的开头
         * @return 匹配返回 true；长度不足时返回 false
         */
        private static boolean startsWith(byte[] data, int[] prefix) {
            if (data.length < prefix.length) {
                return false;
            }
            for (int i = 0; i < prefix.length; i++) {
                // Java 的 byte 是有符号的，0x89 这类大于 127 的值读出来是负数，
                // 必须先与 0xFF 做与运算提升到 0~255 再比
                if ((data[i] & 0xFF) != prefix[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
