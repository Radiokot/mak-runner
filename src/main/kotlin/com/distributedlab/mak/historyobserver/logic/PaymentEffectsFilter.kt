package com.distributedlab.mak.historyobserver.logic

import org.tokend.sdk.api.generated.resources.OpPaymentDetailsResource
import org.tokend.sdk.api.generated.resources.ParticipantEffectResource
import java.util.*
import java.util.function.Predicate

/**
 * Filters out effects not related to Mak
 */
class PaymentEffectsFilter(
    private val startDate: Date,
    private val balanceId: String
) : Predicate<ParticipantEffectResource> {
    override fun test(effect: ParticipantEffectResource): Boolean {
        // Only payments.
        val details = effect.operation.details as? OpPaymentDetailsResource
            ?: return false

        // Only actual.
        if (effect.operation.appliedAt < startDate)
            return false

        // Only incoming.
        if (details.balanceTo.id != balanceId)
            return false

        return true
    }
}