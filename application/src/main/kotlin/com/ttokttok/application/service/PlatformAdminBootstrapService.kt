package com.ttokttok.application.service

import com.ttokttok.application.port.`in`.PlatformAdminBootstrapUseCase
import com.ttokttok.application.port.`in`.PlatformAdminBootstrapUseCase.Result
import com.ttokttok.application.port.`in`.SignUpInstitutionUseCase
import com.ttokttok.application.port.out.PlatformAdminPort
import com.ttokttok.application.port.out.UserPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PlatformAdminBootstrapService(
    private val admins: PlatformAdminPort,
    private val users: UserPort,
    private val signUp: SignUpInstitutionUseCase,
) : PlatformAdminBootstrapUseCase {
    @Transactional
    override fun ensure(command: PlatformAdminBootstrapUseCase.Command): Result {
        if (admins.hasAny()) return Result.ALREADY_EXISTS
        val existing = users.findByEmail(normalizeEmail(command.email))
        if (existing != null) {
            admins.grant(existing.id)
            return Result.GRANTED_EXISTING_USER
        }
        val created = signUp.signUp(
            SignUpInstitutionUseCase.Command(
                institutionName = command.institutionName, ownerName = command.name,
                email = command.email, password = command.password,
            ),
        )
        admins.grant(created.userId)
        return Result.CREATED
    }
}
