package com.veriqra.app.web;

import com.veriqra.app.repository.AuditRepository;
import com.veriqra.app.security.CurrentAccount;
import com.veriqra.app.security.Role;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The audit trail for teachers and administrators.
 *
 * <p>A teacher sees the entries they wrote themselves, an administrator sees the whole
 * trail. The trail is append only, so it is the record of who changed what.
 */
@RestController
@RequestMapping("/api")
public class AuditController {

    private final AuditRepository audit;
    private final CurrentAccount currentAccount;

    public AuditController(AuditRepository audit, CurrentAccount currentAccount) {
        this.audit = audit;
        this.currentAccount = currentAccount;
    }

    /** Lists the audit entries. */
    @GetMapping("/audit-logs")
    public List<AuditRepository.AuditEntry> list(@RequestParam(defaultValue = "100") int limit,
                                                 Authentication authentication) {
        int capped = Math.min(Math.max(limit, 1), 500);
        if (currentAccount.user(authentication).role() == Role.ADMIN) {
            return audit.recent(capped);
        }
        return audit.recentByActor(currentAccount.id(authentication), capped);
    }
}
