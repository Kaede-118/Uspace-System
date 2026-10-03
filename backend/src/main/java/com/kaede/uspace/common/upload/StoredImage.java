package com.kaede.uspace.common.upload;

import java.nio.file.Path;

/**
 * 一张刚落盘的图片：对外怎么访问（{@code url}）+ 它在磁盘上的位置（{@code path}）。
 *
 * <p><b>为什么两样都要带</b>：{@code url} 会被写进库（{@code sys_user.avatar}、
 * {@code biz_product.cover}），是「别人看得见的那一面」；{@code path} 只在本次调用里
 * 活几毫秒，用来收拾残局 —— 比如「写库失败时把刚落盘的图删掉」。
 * 两者由<b>同一次拼装</b>产出，就不会出现「URL 与磁盘路径各算一遍、算出的结果不一致」
 * 这种只能靠猜的错误。
 *
 * <p>不用拿 {@code url} 去反推路径：那要重做一遍路径拼装，而
 * {@link ImageStorage#deleteByUrl} 的前缀校验对「我们自己刚写下去的文件」是纯负债 ——
 * 哪天 {@code url-prefix} 换成 CDN 域名，那条校验只会让它静默地不删。
 *
 * <p>⚠️ <b>{@code path} 绝不能出现在接口响应里</b> —— 那是服务器的目录结构。
 *
 * <p>用 record 而非 Lombok：这是一份「既成事实」，不该有 setter。
 * 项目里 {@code JwtPayload} / {@code UserPrincipal} / {@code LockRecordDto} 同款。
 *
 * @param url  站内相对路径，如 {@code /uploads/avatar/Kaede_1.jpg}
 * @param path 该文件在磁盘上的绝对路径
 */
public record StoredImage(String url, Path path) {
}
