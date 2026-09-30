package com.kaede.uspace.user;

import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.user.dto.UserProfileVo;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 头像与背景图的上传服务（模块 1）。
 *
 * <p>两条独立接口最终都走到本类的 {@link #store}，区别只在落哪个子目录、
 * 写 {@code sys_user} 的哪一列。
 *
 * <p><b>为什么不做成 {@code PUT /api/user/me} 的一个字段</b>：那个接口是
 * 「全量替换」（传 null 即清空）。把图片路径混进去的话，前端提交「只改昵称」
 * 的表单时会因为没带头像路径而<b>把头像清空，且不报任何错</b>。
 * 图片有自己的产生方式（上传），就该有自己的接口。
 *
 * <p>四条实现纪律，下面各处逐条落实：
 * <ol>
 *   <li><b>按文件头判类型，不看 {@code Content-Type}</b> —— 后者是客户端说的，能随便伪造</li>
 *   <li><b>文件名完全由服务端生成，原始文件名一个字都不用</b> ——
 *       路径穿越、{@code shell.jsp}、跨文件系统的编码差异，全都不存在了</li>
 *   <li><b>删旧图之前先校验路径前缀</b>，且必须 {@code normalize()} 之后再比 ——
 *       少了这道，一次换头像就能删掉任意文件</li>
 *   <li><b>删旧图失败不阻断上传</b> —— 换头像这件事已经完成了</li>
 * </ol>
 *
 * @see com.kaede.uspace.common.config.UploadProperties 目录与大小上限的配置来源
 * @see com.kaede.uspace.common.config.WebMvcConfig 让这些文件能被 URL 访问到的映射
 */
@Slf4j
@Service
public class ImageUploadService {

    /** 头像的子目录名。同时也是落库路径里的一段，改它会让历史头像路径失效 */
    private static final String KIND_AVATAR = "avatar";

    /** 背景图的子目录名。同上 */
    private static final String KIND_BANNER = "banner";

    private final UploadProperties uploadProperties;

    private final SysUserMapper userMapper;

    /**
     * 构造器注入，便于单测里直接 {@code new ImageUploadService(配置, 假Mapper)} 组装。
     *
     * @param uploadProperties 上传目录、URL 前缀与大小上限
     * @param userMapper       用户表数据访问
     */
    public ImageUploadService(UploadProperties uploadProperties, SysUserMapper userMapper) {
        this.uploadProperties = uploadProperties;
        this.userMapper = userMapper;
        log.info("[用户] 图片上传服务已就绪，上传目录={}", uploadProperties.getDir());
    }

    // ==================================================================
    // 对外接口
    // ==================================================================

    /**
     * 上传当前用户的头像。
     *
     * @param userId 当前用户 ID，<b>由调用方从凭证里取</b>，不接受路径参数
     * @param file   上传的图片
     * @return 成功时返回<b>完整的</b>用户资料视图（前端一行 {@code store.setUser(resp.data)} 即可刷新）
     */
    public BizResult<UserProfileVo> uploadAvatar(Long userId, MultipartFile file) {
        return store(userId, file, KIND_AVATAR);
    }

    /**
     * 上传当前用户的背景图（约 6:1 的横长图，用作个人卡片背景）。
     *
     * @param userId 当前用户 ID，同 {@link #uploadAvatar}
     * @param file   上传的图片
     * @return 成功时返回完整的用户资料视图
     */
    public BizResult<UserProfileVo> uploadBanner(Long userId, MultipartFile file) {
        return store(userId, file, KIND_BANNER);
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    /**
     * 校验、落盘、写库、清理旧图。
     *
     * <p><b>四步的顺序是有讲究的</b>：先写新图、再改库、最后删旧图。
     * 反过来的话（先删旧、再落新），中间任何一步失败都会让用户
     * 既没有新图、又丢了旧图。按这个顺序，最坏情况是留下一个孤儿文件，
     * 而那是运维问题，不是用户损失。
     *
     * @param userId 当前用户 ID
     * @param file   上传的图片
     * @param kind   子目录名，取 {@link #KIND_AVATAR} 或 {@link #KIND_BANNER}
     * @return 成功时返回更新后的用户资料视图
     */
    private BizResult<UserProfileVo> store(Long userId, MultipartFile file, String kind) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }

        // --- 1. 校验 ---
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

        // --- 2. 落盘 ---
        // 文件名 = {用户名}_{用户 ID}.{扩展名}，不再套一层日期目录：
        //   - 用户名与 ID 都已唯一，两者拼起来天然不重名，日期目录起不到去重作用
        //   - 单店量级下用户数在千级，一个目录放得下（分日期目录是为了应对几十万文件）
        //   - 用户名受注册校验约束（只允许字母、数字、下划线，3~20 字符），
        //     可以安全地进文件名 —— 这一点不能想当然，换成昵称就不行了
        Path root = uploadRoot();
        String fileName = user.getUsername() + "_" + userId + "." + type.extension();
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

        // --- 3. 写库 ---
        String url = uploadProperties.getUrlPrefix() + "/" + kind + "/" + fileName;
        boolean avatar = KIND_AVATAR.equals(kind);
        String oldUrl = avatar ? user.getAvatar() : user.getBanner();

        int affected = avatar
                ? userMapper.updateAvatar(userId, url)
                : userMapper.updateBanner(userId, url);

        if (affected == 0) {
            // 用户在这一瞬间被删掉了。刚落盘的新图没有任何记录指向它，
            // 不清理的话它会永远留在 uploads 目录里，没人知道该不该删。
            deleteQuietly(target, "用户已不存在，回滚刚落盘的新图");
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }
        log.info("[用户] {} 已更新 userId={} url={}", avatar ? "头像" : "背景图", userId, url);

        // --- 4. 清理旧图 ---
        // ⚠️ 文件名固定成 {用户名}_{ID}.{扩展名} 之后，同一用户重传【同一种格式】
        // 会落到同一个路径上 —— 上面那次写入已经把旧内容覆盖掉了。
        // 此时若还去「删旧图」，删掉的正是刚写进去的那张，结果是用户既没有新图、
        // 也没有旧图，而接口返回 200 说成功了。
        //
        // 只有扩展名变了（png → jpg）才会走到删除 —— 那种情况下新图落在另一个路径，
        // 旧文件确实需要清掉。
        if (!url.equals(oldUrl)) {
            deleteOldImage(oldUrl);
        }

        // 重新查一次而不是在内存里改实体：updated_at 由 SQL 的 NOW() 填充，
        // 手工拼一个实体会让返回的更新时间与库里不一致
        SysUser updated = userMapper.selectById(userId);
        return BizResult.ok(UserProfileVo.from(updated));
    }

    // ==================================================================
    // 旧图清理
    // ==================================================================

    /**
     * 删除被替换掉的旧图。
     *
     * <p>⚠️ <b>删之前必须校验路径前缀</b>：只删 {@code uspace.upload.dir} 之下的文件。
     * 少了这道校验，一旦 {@code sys_user.avatar} 被改成 {@code /etc/passwd}
     * 或 {@code ../../conf/server.xml}，一次换头像就能删掉任意文件 —— 而且不报错。
     * 校验必须用 {@code Path.normalize()} 之后再比前缀，不能只比字符串开头
     * （{@code /uploads/../etc/passwd} 是能通过字符串前缀检查的）。
     *
     * <p>删除失败只记日志，不影响上传成功 —— 换头像这件事已经完成了，
     * 旧文件没删掉是运维问题，不该让用户看到报错。
     *
     * <p>与本项目全局规矩的关系：全局约定是「未经许可不删文件、删除走回收站」。
     * 这里删的是<b>应用自己产生的运行期上传文件</b>（不是源码、不是文档），
     * 且用户已明确指示自动删。仍保留两道保险：路径前缀校验（防越界删除）
     * 与失败不阻断（防误伤主流程）。
     *
     * @param oldUrl 旧图的站内相对路径，可为 null（该用户还没传过图）
     */
    private void deleteOldImage(String oldUrl) {
        if (oldUrl == null || oldUrl.isBlank()) {
            return;
        }
        String prefix = uploadProperties.getUrlPrefix();

        // 不以前缀开头的一律不碰。合法值只会是本服务自己写进去的那种路径，
        // 出现别的取值说明这条记录被人工改过 —— 那不是我们该删的东西
        if (!oldUrl.startsWith(prefix + "/")) {
            log.warn("[用户] 旧图路径不在上传前缀 {} 之下，拒绝删除：{}", prefix, oldUrl);
            return;
        }

        Path root = uploadRoot();
        Path target = root.resolve(oldUrl.substring(prefix.length() + 1)).normalize();

        // ⚠️ 必须 normalize 之后再比 —— "/uploads/../etc/passwd" 能通过上面那道字符串检查
        if (!target.startsWith(root)) {
            log.warn("[用户] 旧图路径越出上传目录，拒绝删除：{} —— 已归一化为 {}", oldUrl, target);
            return;
        }

        deleteQuietly(target, "删除被替换的旧图");
    }

    /**
     * 尽力删除一个文件，失败只记日志。
     *
     * <p>用于两处：清理旧图（失败不影响用户）、回滚刚落盘的新图（失败更无所谓，
     * 反正它已经是个孤儿了）。两处的共同点是「删不掉也不该让用户看到报错」，
     * 所以合成一个方法。
     *
     * @param path   要删的文件
     * @param reason 记日志时说明这是什么场景下的删除
     */
    private static void deleteQuietly(Path path, String reason) {
        try {
            boolean deleted = Files.deleteIfExists(path);
            if (!deleted) {
                log.debug("[用户] {}：文件本就不存在 {}", reason, path);
            }
        } catch (IOException e) {
            log.warn("[用户] {} 失败，不影响主流程：{}", reason, path, e);
        }
    }

    /**
     * 取上传根目录的绝对路径（已归一化）。
     *
     * @return 上传根目录
     */
    private Path uploadRoot() {
        return Paths.get(uploadProperties.getDir()).toAbsolutePath().normalize();
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
