package com.paynova.auth;

import com.paynova.account.AccountService;
import com.paynova.audit.AuditService;
import com.paynova.common.ApiException;
import com.paynova.common.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AuthService {

    /** At least 8 characters, containing both letters and digits. */
    static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AccountService accountService;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AccountService accountService,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.accountService = accountService;
        this.auditService = auditService;
    }

    /**
     * Registration. Step 2 additionally creates user:{id}:wallet within this transaction
     * (design doc §4) — wallet creation must be atomic with user creation, hence this
     * method remains @Transactional.
     */
    @Transactional
    public User register(String email, String rawPassword) {
        String normalizedEmail = normalize(email);
        if (!PASSWORD_POLICY.matcher(rawPassword).matches()) {
            throw new ApiException(ErrorCode.WEAK_PASSWORD,
                    "password must be at least 8 characters with letters and digits");
        }
        // The fast-fail check is only a friendly hint; correctness is ultimately guaranteed
        // by the unique constraint on users.email (TOCTOU: two concurrent requests can both
        // pass this check, and one of them will hit the unique constraint at the flush below)
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ApiException(ErrorCode.EMAIL_EXISTS, "email already registered");
        }
        try {
            // saveAndFlush: surface a unique-constraint conflict right here instead of at commit time
            User user = userRepository.saveAndFlush(
                    new User(normalizedEmail, passwordEncoder.encode(rawPassword)));
            // Create the wallet in the same transaction (§4): user and wallet are created
            // atomically — a user without a wallet can never exist
            accountService.createWallet(user.getId());
            auditService.success("auth.register", user.getId(), user.getRole().name(),
                    null, null, null, null, null, null, null);
            return user;
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate registration → map deterministically to 409 instead of
            // falling through to the catch-all handler as a 500. Note: we catch and immediately
            // rethrow a business exception, so the whole transaction still rolls back and no
            // rollback-only issue arises — fundamentally different from idempotent writes,
            // which must continue executing after the catch (design doc §8)
            throw new ApiException(ErrorCode.EMAIL_EXISTS, "email already registered");
        }
    }

    // Not readOnly: the success audit INSERT below must join this transaction (§11)
    @Transactional
    public String login(String email, String rawPassword) {
        User user = userRepository.findByEmail(normalize(email))
                .filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                // Uniform error message: do not reveal whether the email exists or the
                // password is wrong, to prevent account enumeration
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_CREDENTIALS, "invalid email or password"));
        auditService.success("auth.login", user.getId(), user.getRole().name(),
                null, null, null, null, null, null, null);
        return jwtService.generate(user.getId(), user.getEmail(), user.getRole());
    }

    /** Email normalization: User@Example.com and user@example.com are the same account. */
    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
