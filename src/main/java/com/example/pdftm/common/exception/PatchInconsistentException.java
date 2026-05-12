package com.example.pdftm.common.exception;

import com.example.pdftm.common.error.ErrorCode;

/**
 * patch 应用到 currentModel 的结果与 LLM 同时给出的 newModel 不一致。
 * 这是模型"嘴上说改 A 实际改了 B"或"漏写一条 patch"的征兆，**绝不能跳过**——
 * 跳过意味着审计链断了，未来回滚会出事。
 *
 * 继承 {@link LlmOutputInvalidException}，使重试模板只 catch 一种异常即可覆盖"输出可重试"全集。
 */
public class PatchInconsistentException extends LlmOutputInvalidException {

    public PatchInconsistentException() {
        super(ErrorCode.EDIT_PATCH_INCONSISTENT);
    }

    public PatchInconsistentException(ErrorCode code, Object... args) {
        super(code, args);
    }

    /** @deprecated 使用 {@link #PatchInconsistentException()} 或带 {@link ErrorCode} 的构造器 */
    @Deprecated
    public PatchInconsistentException(String message) {
        super(message);
    }
}
