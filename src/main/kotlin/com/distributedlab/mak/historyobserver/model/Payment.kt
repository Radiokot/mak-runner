package com.distributedlab.mak.historyobserver.model

import org.tokend.sdk.api.generated.resources.OpPaymentDetailsResource
import org.tokend.sdk.api.generated.resources.ParticipantEffectResource
import java.math.BigDecimal

/**
 * Mak-related payment - payment OP of Mak asset with filled subject.
 */
class Payment(
    val id: String,
    val amount: BigDecimal,
    val referrer: String,
    val sourceAccountId: String
) {
    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other is Payment && other.id == this.id
    }

    companion object {
        fun fromEffect(effect: ParticipantEffectResource): Payment? {
            val details = effect.operation.details as? OpPaymentDetailsResource
                ?: return null

            var subject = details.subject
                ?.trim()
                ?.replace(Regex.fromLiteral("[\n\r]"), "")
                ?.replace(" ", "")
                ?.takeIf(String::isNotBlank)
                ?: return null

            if (subject.contains("subject")) {
                subject = subject.substringAfter("{\"subject\":\"")
                subject = subject.substringBefore("\"}")
            }

            return Payment(
                id = effect.id,
                referrer = subject,
                amount = details.amount,
                sourceAccountId = details.accountFrom.id
            )
        }
    }
}