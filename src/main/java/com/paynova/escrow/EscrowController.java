package com.paynova.escrow;

import com.paynova.idempotency.IdempotencyKeys;
import com.paynova.idempotency.IdempotencyService;
import com.paynova.idempotency.RequestHash;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Step 1 exposes only the two non-money endpoints: create (#6) and get (#10).
 * fund/release/refund (#7–#9) are wired up in Step 4, once the ledger and locking are in place (design doc §12).
 */
@RestController
@RequestMapping("/api/escrows")
public class EscrowController {

    public record CreateEscrowRequest(@NotNull Long seller_id,
                                      @NotNull Long amount_cents,
                                      String description) {}

    private final EscrowService escrowService;
    private final IdempotencyService idempotencyService;

    public EscrowController(EscrowService escrowService, IdempotencyService idempotencyService) {
        this.escrowService = escrowService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String keyHeader,
            @Valid @RequestBody CreateEscrowRequest request,
            Authentication authentication) {
        Long buyerId = (Long) authentication.getPrincipal();
        UUID key = IdempotencyKeys.parse(keyHeader);
        String hash = RequestHash.of("POST", "/api/escrows", request);

        IdempotencyService.Result result = idempotencyService.execute(buyerId, key, hash, () -> {
            EscrowOrder order = escrowService.create(
                    buyerId, request.seller_id(), request.amount_cents(), request.description());
            return new IdempotencyService.Result(201, toBody(order), order.getId().toString());
        });
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** §9 API #7–#9: the three money endpoints. All require an Idempotency-Key; idempotency + locking + CAS + ledger posting happen within a single transaction. */
    @PostMapping("/{id}/fund")
    public ResponseEntity<Map<String, Object>> fund(
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String keyHeader,
            @PathVariable UUID id, Authentication authentication) {
        return moneyAction(keyHeader, id, authentication, "fund");
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<Map<String, Object>> release(
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String keyHeader,
            @PathVariable UUID id, Authentication authentication) {
        return moneyAction(keyHeader, id, authentication, "release");
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<Map<String, Object>> refund(
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String keyHeader,
            @PathVariable UUID id, Authentication authentication) {
        return moneyAction(keyHeader, id, authentication, "refund");
    }

    private ResponseEntity<Map<String, Object>> moneyAction(String keyHeader, UUID id,
                                                            Authentication authentication,
                                                            String action) {
        Long actorId = (Long) authentication.getPrincipal();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        UUID key = IdempotencyKeys.parse(keyHeader);
        String hash = RequestHash.of("POST", "/api/escrows/" + id + "/" + action, null);

        IdempotencyService.Result result = idempotencyService.execute(actorId, key, hash, () -> {
            EscrowOrder order = switch (action) {
                case "fund" -> escrowService.fund(id, actorId);
                case "release" -> escrowService.release(id, actorId);
                case "refund" -> escrowService.refund(id, actorId, isAdmin);
                default -> throw new IllegalArgumentException("unknown action: " + action);
            };
            return new IdempotencyService.Result(200, toBody(order), id.toString());
        });
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id, Authentication authentication) {
        Long requesterId = (Long) authentication.getPrincipal();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return ResponseEntity.ok(toBody(escrowService.getAuthorized(id, requesterId, isAdmin)));
    }

    private Map<String, Object> toBody(EscrowOrder order) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("id", order.getId());
        body.put("buyer_id", order.getBuyerId());
        body.put("seller_id", order.getSellerId());
        body.put("amount_cents", order.getAmountCents());
        body.put("currency", order.getCurrency().trim());
        body.put("description", order.getDescription());
        body.put("status", order.getStatus().name());
        body.put("created_at", order.getCreatedAt());
        return body;
    }
}
