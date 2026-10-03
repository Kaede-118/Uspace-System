package com.kaede.uspace.user;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.upload.ImageStorage;
import com.kaede.uspace.common.upload.StoredImage;
import com.kaede.uspace.user.dto.UserProfileVo;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
 * <p><b>本类只负责「写到哪个用户的哪一列」这一层</b>：校验、判类型、落盘、
 * 按路径删文件都在公共层的 {@link ImageStorage} 里（商品封面走的是同一个组件，
 * 所以那段安全代码只有一份）。
 *
 * <p>四条实现纪律 —— <b>实现均在 {@link ImageStorage}</b>，这里只留摘要：
 * <ol>
 *   <li><b>按文件头判类型，不看 {@code Content-Type}</b> —— 后者是客户端说的，能随便伪造
 *       （见 {@code ImageStorage.ImageType}）</li>
 *   <li><b>文件名完全由服务端生成，原始文件名一个字都不用</b> ——
 *       路径穿越、{@code shell.jsp}、跨文件系统的编码差异，全都不存在了</li>
 *   <li><b>删旧图之前先校验路径前缀</b>，且必须 {@code normalize()} 之后再比 ——
 *       少了这道，一次换头像就能删掉任意文件（见 {@link ImageStorage#deleteByUrl}）</li>
 *   <li><b>删旧图失败不阻断上传</b> —— 换头像这件事已经完成了</li>
 * </ol>
 *
 * <p>⚠️ 还有一条纪律是<b>本类专属</b>的，没有跟着搬去公共层：
 * 文件名固定 → 重传同一种格式会落到同一个路径 → 「删旧图」必须先判断新旧路径是否相同。
 * 见 {@link #store} 里那段说明。
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

    private final SysUserMapper userMapper;

    private final ImageStorage imageStorage;

    /**
     * 构造器注入，便于单测里直接 {@code new ImageUploadService(假Mapper, new ImageStorage(配置))} 组装。
     *
     * @param userMapper   用户表数据访问
     * @param imageStorage 公共层的图片落盘组件
     */
    public ImageUploadService(SysUserMapper userMapper, ImageStorage imageStorage) {
        this.userMapper = userMapper;
        this.imageStorage = imageStorage;
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
     * 落盘、写库、清理旧图。
     *
     * <p><b>三步的顺序是有讲究的</b>：先写新图、再改库、最后删旧图。
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

        // --- 1. 落盘 ---
        // 文件名 = {用户名}_{用户 ID}.{扩展名}，不再套一层日期目录：
        //   - 用户名与 ID 都已唯一，两者拼起来天然不重名，日期目录起不到去重作用
        //   - 单店量级下用户数在千级，一个目录放得下（分日期目录是为了应对几十万文件）
        //   - 用户名受注册校验约束（只允许字母、数字、下划线，3~20 字符），
        //     可以安全地进文件名 —— 这一点不能想当然，换成昵称就不行了
        //     （商品封面就是因为商品名不可控，只好整个改用 UUID）
        BizResult<StoredImage> stored = imageStorage.store(
                kind, user.getUsername() + "_" + userId, file);
        if (!stored.isSuccess()) {
            // 连附加文案一起透传：「请选择要上传的图片」那句提示要靠它才留得住
            return BizResult.fail(stored.getError(), stored.getMessage());
        }
        StoredImage image = stored.getData();

        // --- 2. 写库 ---
        boolean avatar = KIND_AVATAR.equals(kind);
        String oldUrl = avatar ? user.getAvatar() : user.getBanner();

        int affected = avatar
                ? userMapper.updateAvatar(userId, image.url())
                : userMapper.updateBanner(userId, image.url());

        if (affected == 0) {
            // 用户在这一瞬间被删掉了。刚落盘的新图没有任何记录指向它，
            // 不清理的话它会永远留在 uploads 目录里，没人知道该不该删。
            // 用 path 而不是 deleteByUrl：那是我们自己刚写下去的文件，
            // 不需要（也不该）再过一遍路径前缀校验
            imageStorage.deleteQuietly(image.path(), "用户已不存在，回滚刚落盘的新图");
            return BizResult.fail(ErrorCode.USER_NOT_FOUND);
        }
        log.info("[用户] {} 已更新 userId={} url={}", avatar ? "头像" : "背景图", userId, image.url());

        // --- 3. 清理旧图 ---
        // ⚠️ 文件名固定成 {用户名}_{ID}.{扩展名} 之后，同一用户重传【同一种格式】
        // 会落到同一个路径上 —— 上面那次写入已经把旧内容覆盖掉了。
        // 此时若还去「删旧图」，删掉的正是刚写进去的那张，结果是用户既没有新图、
        // 也没有旧图，而接口返回 200 说成功了。
        //
        // 只有扩展名变了（png → jpg）才会走到删除 —— 那种情况下新图落在另一个路径，
        // 旧文件确实需要清掉。
        if (!image.url().equals(oldUrl)) {
            imageStorage.deleteByUrl(oldUrl);
        }

        // 重新查一次而不是在内存里改实体：updated_at 由 SQL 的 NOW() 填充，
        // 手工拼一个实体会让返回的更新时间与库里不一致
        SysUser updated = userMapper.selectById(userId);
        return BizResult.ok(UserProfileVo.from(updated));
    }
}
