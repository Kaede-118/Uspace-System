package com.kaede.uspace.qqbot.protocol;

/**
 * 一条消息里的图片（模块 11），从消息段里抽出来的两个字段。
 *
 * @param file 文件名或协议端给的标识。<b>不能当路径用</b> ——
 *             它可能只是个不含目录的名字，也可能是 {@code xxx.jpg} 这样的相对值
 * @param url  可下载的地址。<b>取图走它</b>；为 null 时这张图拿不到
 *             （协议端版本不同，这个字段未必总有），调用方应跳过而不是报错
 */
public record OneBotImage(String file, String url) {
}
