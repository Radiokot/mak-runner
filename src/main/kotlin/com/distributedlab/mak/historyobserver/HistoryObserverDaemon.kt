package com.distributedlab.mak.historyobserver

import com.distributedlab.mak.historyobserver.model.Payment
import org.tokend.sdk.api.base.model.DataPage
import org.tokend.sdk.api.base.params.PagingOrder
import org.tokend.sdk.api.base.params.PagingParamsV2
import org.tokend.sdk.api.generated.resources.OpPaymentDetailsResource
import org.tokend.sdk.api.generated.resources.ParticipantEffectResource
import org.tokend.sdk.api.v3.history.HistoryApi
import org.tokend.sdk.api.v3.history.params.ParticipantEffectsPageParams
import org.tokend.sdk.api.v3.history.params.ParticipantEffectsParams
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

/**
 *
 */
class HistoryObserverDaemon(
    private val balanceId: String,
    private val startDate: Date,
    private val signedHistoryApi: HistoryApi,
    private val newPaymentsCallback: (newPayments: Collection<Payment>) -> Unit
): Thread() {
    init {
        isDaemon = true
    }

    private var cursor: String? = null

    override fun run() {
        while (!isInterrupted) {
            processNextPage()
        }
    }

    private fun processNextPage() {
        var page: DataPage<ParticipantEffectResource>
        while (true) {
            try {
                page = getNextPage()
                break
            } catch (e: Exception) {
                Logger.getGlobal().log(Level.WARNING, "Failed to get page (${e.message}), " +
                        "retry in $RETRY_INTERVAL_MS ms")
            }
        }

        cursor = page.nextCursor

        val payments = page
            .items
            .mapNotNull { effect ->
                val paymentDetails = effect.operation.details as? OpPaymentDetailsResource
                    ?: return@mapNotNull null

                if (effect.operation.appliedAt < startDate) {
                    return@mapNotNull null
                }

                // Only process incoming payments.
                if (paymentDetails.balanceTo.id != balanceId) {
                    return@mapNotNull null
                }

                val subject = paymentDetails.subject
                    ?.trim()
                    ?.replace(Regex.fromLiteral("[\n\r]"), "")
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null

                Payment(
                    id = effect.id,
                    referrer = subject,
                    amount = paymentDetails.amount
                )
            }

        if (payments.isNotEmpty()) {
            newPaymentsCallback(payments)
        }

        if (page.isLast) {
            sleep(POLL_INTERVAL_MS)
        }
    }

    private fun getNextPage(): DataPage<ParticipantEffectResource> {
        return signedHistoryApi.get(
            ParticipantEffectsPageParams(
                balance = balanceId,
                include = listOf(
                    ParticipantEffectsParams.Includes.OPERATION,
                    ParticipantEffectsParams.Includes.OPERATION_DETAILS
                ),
                pagingParams = PagingParamsV2(
                    page = cursor,
                    limit = PAGE_LIMIT,
                    order = PagingOrder.ASC
                )
            )
        )
            .execute()
            .get()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5000L
        private const val RETRY_INTERVAL_MS = 2000
        private const val PAGE_LIMIT = 40
    }
}