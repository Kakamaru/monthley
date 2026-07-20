package com.monthley.account.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

interface AccountInvitationRepository extends JpaRepository<AccountInvitation, Long> {
    List<AccountInvitation> findByEmailAndStatus(String email, AccountInvitation.Status status);
    List<AccountInvitation> findByAccountIdAndStatus(Long accountId, AccountInvitation.Status status);
}
