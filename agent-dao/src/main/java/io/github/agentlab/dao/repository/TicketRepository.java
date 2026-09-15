package io.github.agentlab.dao.repository;

import io.github.agentlab.dao.entity.TicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<TicketEntity, String> {

    /**
     * 幂等回查：并发击穿 DB 唯一约束后，靠它找到"已经被别人创建"的那条工单。
     */
    Optional<TicketEntity> findByIdempotencyKey(String idempotencyKey);
}
