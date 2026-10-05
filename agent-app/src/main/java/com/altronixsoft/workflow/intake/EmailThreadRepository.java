package com.altronixsoft.workflow.intake;

import org.springframework.data.jpa.repository.JpaRepository;

interface EmailThreadRepository extends JpaRepository<EmailThread, String> {}
