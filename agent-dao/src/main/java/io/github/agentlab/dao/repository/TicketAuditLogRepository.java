package io.github.agentlab.dao.repository;

import io.github.agentlab.dao.entity.TicketAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketAuditLogRepository extends JpaRepository<TicketAuditLogEntity, Long> {
}
