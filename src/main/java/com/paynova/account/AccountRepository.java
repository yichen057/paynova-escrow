package com.paynova.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByName(String name);

    /**
     * The only mutation path for the balance snapshot: an atomic incremental UPDATE
     * (balance = balance + delta).
     * - Atomicity: concurrent top-ups never overwrite each other (lost updates require
     *   read-modify-write, which this is not)
     * - Non-negativity: the CHECK (allow_negative OR balance >= 0) on the accounts table
     *   is the backstop; a violation throws and rolls back the whole transaction
     *   (the pessimistic lock from Step 4 yields a clean 422 before it gets that far)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE accounts SET balance = balance + :delta WHERE id = :id", nativeQuery = true)
    int applyDelta(@Param("id") Long id, @Param("delta") long delta);
}
