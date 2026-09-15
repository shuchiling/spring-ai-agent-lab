-- agent-dao 建表脚本（任务 04：售后工单）
-- 注意：开发期用 spring.jpa.hibernate.ddl-auto=update 由 Hibernate 同步表结构，
--       本脚本作为表结构的权威说明（source of truth），便于核对字段、索引、约束设计。
--       生产环境建议用 Flyway/Liquibase 管理，不要依赖 ddl-auto。

-- 工单主表
CREATE TABLE IF NOT EXISTS tickets (
    ticket_id        VARCHAR(64)  NOT NULL PRIMARY KEY,
    order_no         VARCHAR(32)  NOT NULL,
    reason           TEXT         NOT NULL,
    priority         VARCHAR(16)  NOT NULL,                       -- TicketPriority 枚举名
    status           VARCHAR(16)  NOT NULL,                       -- TicketStatus 枚举名
    idempotency_key  VARCHAR(64)  NOT NULL,                       -- sha256(orderNo + reason)
    trace_id         VARCHAR(64),                                -- 创建时的 traceId
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 幂等兜底的最后一道墙：同一 idempotency_key 只能落一条工单
    CONSTRAINT uk_tickets_idempotency_key UNIQUE (idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_tickets_order_no ON tickets (order_no);

-- 工单审计日志表
CREATE TABLE IF NOT EXISTS ticket_audit_log (
    audit_id    BIGSERIAL    PRIMARY KEY,
    ticket_id   VARCHAR(64),                                    -- 草稿阶段可为空
    action      VARCHAR(32)  NOT NULL,                          -- DRAFT_CREATED / TICKET_CONFIRMED
    operator    VARCHAR(64),                                    -- 任务 12 做权限时填
    trace_id    VARCHAR(64)  NOT NULL,
    payload     TEXT,                                           -- 草稿/工单快照 JSON
    occurred_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_ticket_id ON ticket_audit_log (ticket_id);
