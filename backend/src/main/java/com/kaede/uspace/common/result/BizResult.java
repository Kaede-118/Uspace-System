package com.kaede.uspace.common.result;

import lombok.Data;

/**
 * Service 层的业务结果。
 *
 * <p>本项目的约定是：<b>参数错误抛异常，业务失败走返回值</b>。
 * 前者是「调用方式不对」，属于编程错误，应当尽早暴露；
 * 后者是「业务规则不接受」，属于正常流程分支，用返回值表达更清爽 ——
 * 调用方一眼能看出哪些失败是可以预期的，不必靠 try-catch 去猜。
 *
 * <p>形态上刻意对齐 {@code lock/dto/PasscodeResult}：那里是为了贴合通通锁云 API
 * 的返回体，这里是全项目通用的业务结果，两者思路一致。
 *
 * <p><b>本类不直接返回给 HTTP</b>。Controller 拿到它之后交给
 * {@link ApiResult#of(BizResult)} 转换，由那一处统一决定 HTTP 状态码与响应体结构。
 * 这样「Service 层不知道 HTTP」这条边界才守得住。
 *
 * @param <T> 业务数据的类型
 */
@Data
public class BizResult<T> {

    /** 是否成功 */
    private boolean success;

    /** 失败时的错误码。成功时为 null */
    private ErrorCode error;

    /**
     * 附加说明。为空时取 {@link ErrorCode#getMessage()} 的默认文案。
     *
     * <p>用于补充上下文，例如「密码已连续错误 3 次」。
     * <b>注意不要把内部细节写进来</b> —— 这个字段最终会返回给前端。
     */
    private String message;

    /** 成功时的业务数据，失败时为 null */
    private T data;

    /**
     * 构造成功结果。
     *
     * @param data 业务数据，可为 null（如删除操作没有返回数据）
     * @param <T>  业务数据类型
     * @return 成功的结果对象
     */
    public static <T> BizResult<T> ok(T data) {
        BizResult<T> result = new BizResult<>();
        result.setSuccess(true);
        result.setData(data);
        return result;
    }

    /**
     * 构造失败结果，使用错误码的默认文案。
     *
     * @param error 错误码，不能为空
     * @param <T>   业务数据类型
     * @return 失败的结果对象
     */
    public static <T> BizResult<T> fail(ErrorCode error) {
        return fail(error, null);
    }

    /**
     * 构造失败结果，并附上自定义说明。
     *
     * @param error   错误码，不能为空
     * @param message 附加说明，为空时回退到错误码的默认文案
     * @param <T>     业务数据类型
     * @return 失败的结果对象
     */
    public static <T> BizResult<T> fail(ErrorCode error, String message) {
        BizResult<T> result = new BizResult<>();
        result.setSuccess(false);
        result.setError(error);
        result.setMessage(message);
        return result;
    }

    /**
     * 取最终对外展示的提示文案。
     *
     * @return 自定义说明；未设置时返回错误码的默认文案；成功时返回 null
     */
    public String resolveMessage() {
        if (success) {
            return null;
        }
        if (message != null && !message.isBlank()) {
            return message;
        }
        return error == null ? ErrorCode.INTERNAL_ERROR.getMessage() : error.getMessage();
    }
}
