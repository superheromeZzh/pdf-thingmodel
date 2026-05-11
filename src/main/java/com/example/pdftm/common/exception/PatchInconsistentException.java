package com.example.pdftm.common.exception;

/**
 * patch 应用到 currentModel 的结果与 LLM 同时给出的 newModel 不一致。
 * 这是模型"嘴上说改 A 实际改了 B"或"漏写一条 patch"的征兆，**绝不能跳过**——
 * 跳过意味着审计链断了，未来回滚会出事。
 */
public class PatchInconsistentException extends RuntimeException {
    public PatchInconsistentException(String message) { super(message); }
}
