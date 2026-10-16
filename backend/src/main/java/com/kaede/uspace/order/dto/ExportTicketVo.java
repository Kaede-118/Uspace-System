package com.kaede.uspace.order.dto;

import lombok.Data;

/**
 * 导出票据：一份已经生成好、放在服务端缓存里的文件。
 *
 * <p>生成与下载分成两步（见 {@code ExportCache} 的类注释），
 * 前端拿到 {@code token} 后立刻去下载接口取文件，用户感受仍是一次点击。
 *
 * <p>{@code fileName} 由后端给：文件名里带着导出范围与时刻
 * （如「计时订单流水_20261016_2245.xlsx」），浏览器下载时用它，
 * 用户拿到的就不是一堆 {@code export(3).xlsx}。
 */
@Data
public class ExportTicketVo {

    /** 下载凭据：拼进 {@code GET /api/admin/exports/{token}} */
    private String token;

    /** 建议的下载文件名（含 .xlsx） */
    private String fileName;

    /** 实际导出的行数（不含表头） */
    private int rows;

    /**
     * 是否因为行数上限被截断。
     *
     * <p>⚠️ 有它前端才能提示「只导了前 N 行，请缩小筛选范围」——
     * 否则用户拿到的是一份<b>看起来完整、实际少了一截</b>的流水，
     * 而基于它算出来的数不会报任何错。
     */
    private boolean truncated;
}
