package com.kaede.uspace.order.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 标记一条对账差异已处理的请求体。
 *
 * <p><b>只有备注一个字段，而且刻意不加 {@code @NotBlank}</b>。
 *
 * <p>与付款凭证的 {@code ProofRejectRequest}（驳回原因必填）是<b>刻意相反</b>的，
 * 因为两者的读者不同：
 * <ul>
 *   <li>驳回原因要展示给<b>顾客</b>，空着的话他反复重交也猜不出哪里不对 ——
 *       所以必填</li>
 *   <li>这里的备注是管理员<b>写给自己的备忘</b>，只有他自己看 —— 所以选填</li>
 * </ul>
 *
 * <p>强制填的后果很具体：面对 50 条「未填流水号」的差异，管理员会逐个打上
 * 「已处理」三个字，然后这个功能就没人用了。而一个没人用的标记功能，
 * 比一个允许留空的功能糟得多 —— 它会让差异列表永远清不掉。
 *
 * <p>长度上限与 {@code biz_reconcile_diff.handle_note VARCHAR(200)} 对齐。
 * 在接口层挡住，而不是让数据库抛 {@code Data too long} —— 后者返回的是 500，
 * 而这是一次完全正常的操作失误。
 */
@Data
public class HandleDiffRequest {

    /** 处理备注，选填，最多 200 字 */
    @Size(max = 200, message = "备注不能超过 200 字")
    private String note;
}
