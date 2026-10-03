package com.kaede.uspace.order.dto;

/**
 * 账单原文件下载的结果。
 *
 * <p><b>它不是 JSON 响应体</b>（那个包里其余的都是），而是
 * {@code ReconcileService#loadBillFile} 交给 Controller 的载体 ——
 * Controller 拿它去设 {@code Content-Disposition} 与 {@code Content-Type}，
 * 然后把 {@link #content} 直接写进响应流。
 *
 * <p>做成一个对象而不是让 Controller 自己查两次（先查批次拿文件名、再读文件），
 * 是因为那两次查询中间批次可能已被清理，而那时文件名与文件内容就对不上了 ——
 * 一次取回来的两个值天然是配套的。
 *
 * @param fileName 下载时建议的文件名，取自批次上记录的原始文件名
 * @param content  文件内容
 */
public record BillFileVo(String fileName, byte[] content) {
}
