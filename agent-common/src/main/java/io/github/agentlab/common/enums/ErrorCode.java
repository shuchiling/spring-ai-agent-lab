package io.github.agentlab.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ErrorCode {
    BAD_REQUEST("400", "请求参数错误"),
    ILLEGAL_ARGUMENT("1001", "非法参数"),
    INTERNAL_ERROR("1000", "系统异常");

    private final String code;
    private final String message;
}
