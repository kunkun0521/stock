package com.li.stock.prob.api;

/**
 * 概率引擎统一响应外壳（文档 §10.3）：{code, message, data, disclaimer}。
 *
 * <p>前端 client.js 只判断 code==0 并取 data，额外的 disclaimer 字段不影响现有封装。
 * 项目其它模块仍用 {@code ApiResponse}，这里单独定义是为了满足「接口必须带免责声明」的硬要求，
 * 不去改动全局响应结构。</p>
 *
 * @param code       0 成功，其它为业务错误码
 * @param message    提示
 * @param data       业务数据
 * @param disclaimer 固定免责声明文案
 */
public record ProbResponse<T>(int code, String message, T data, String disclaimer) {

    public static final String DISCLAIMER =
            "本系统输出为基于历史数据的统计估计，仅供量化研究参考，不构成任何投资建议。"
                    + "历史概率不代表未来表现，投资有风险。";

    public static <T> ProbResponse<T> ok(T data) {
        return new ProbResponse<>(0, "ok", data, DISCLAIMER);
    }

    public static <T> ProbResponse<T> fail(int code, String message) {
        return new ProbResponse<>(code, message, null, DISCLAIMER);
    }
}
