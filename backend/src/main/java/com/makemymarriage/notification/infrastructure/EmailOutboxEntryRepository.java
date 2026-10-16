package com.makemymarriage.notification.infrastructure;

import com.makemymarriage.notification.domain.EmailOutboxEntry;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailOutboxEntryRepository extends JpaRepository<EmailOutboxEntry, UUID> {}
