package io.github.agentlab.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ErrorCode {
    BAD_REQUEST("400", "请求参数错误"),
    ILLEGAL_ARGUMENT("1001", "非法参数"),
    INTERNAL_ERROR("1000", "系统异常"),
    // 工单相关（任务 04）
    TICKET_DRAFT_NOT_FOUND("2001", "草稿不存在或已过期"),
    TICKET_DRAFT_EXPIRED("2002", "草稿已过期，请重新发起"),
    ORDER_NOT_FOUND("2003", "订单不存在"),
    ORDER_NOT_IN_AFTER_SALES("2004", "订单不在售后期内"),
    TICKET_CREATE_FAILED("2005", "工单创建失败"),
    TICKET_CONFIRM_IN_PROGRESS("2006", "工单正在处理中，请稍后重试");

    private final String code;
    private final String message;
}
