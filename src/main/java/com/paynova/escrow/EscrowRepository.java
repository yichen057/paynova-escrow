package com.paynova.escrow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface EscrowRepository extends JpaRepository<EscrowOrder, UUID> {

    /**
     * The single write path for state transitions: a conditional UPDATE (CAS, design doc §6).
     *
     * Returns the affected row count — callers MUST check == 1; == 0 means the current status
     * is no longer `from` (concurrent race or duplicate request), and that branch must produce
     * no side effects.
     *
     * Design decision (Step 1): one generic method rather than one method per transition —
     * legality is already guaranteed by EscrowStatus.nextFor() before the SQL runs;
     * the SQL is responsible only for concurrency correctness.
     *
     * clearAutomatically: clears the first-level cache after this bulk update (which bypasses the
     * entity cache), preventing subsequent reads from seeing a stale pre-transition snapshot.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE escrow_orders
            SET status = :to, updated_at = now()
            WHERE id = :id AND status = :from
            """, nativeQuery = true)
    int transitionRaw(@Param("id") UUID id,
                      @Param("from") String from,
                      @Param("to") String to);

    /**
     * Native SQL rather than JPQL: status/updated_at are marked updatable=false on the entity
     * (to block any "read, set, save" bypass path). JPQL bulk updates behave inconsistently across
     * Hibernate versions for such columns; native SQL bypasses the entity mapping entirely and
     * behaves deterministically.
     */
    default int transition(UUID id, EscrowStatus from, EscrowStatus to) {
        return transitionRaw(id, from.name(), to.name());
    }
}
