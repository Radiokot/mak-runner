package com.distributedlab.mak.balancesobserver

import com.distributedlab.mak.api.Api
import org.tokend.sdk.api.v3.balances.params.BalanceParams
import org.tokend.sdk.utils.BigDecimalUtil
import java.math.BigDecimal
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Checks given [balances] for amount change,
 * invokes [amountChangesCallback] with changed balances
 */
class BalancesObserverDaemon(
    private val balances: Collection<String>,
    private val masterSignedApi: Api,
    private val amountChangesCallback: (Map<String, BigDecimal>) -> Unit
) : Thread() {
    private val knownAmounts: MutableMap<String, BigDecimal> = mutableMapOf()

    init {
        isDaemon = true
    }

    override fun run() {
        while (true) {
            if (!isInterrupted) {
                val startTime = System.currentTimeMillis()

                checkBalances()

                val delta = System.currentTimeMillis() - startTime
                if (delta < POLL_INTERVAL_MS) {
                    sleep(POLL_INTERVAL_MS - delta)
                }
            }
        }
    }

    private fun checkBalances() {
        try {
            val currentAmounts = balances.map { balanceId ->
                balanceId to masterSignedApi.v3.balances
                    .getById(balanceId, BalanceParams(listOf("state")))
                    .execute()
                    .get()
                    .state
                    .available
                    .let { BigDecimalUtil.stripTrailingZeros(it) }
            }

            val newAmounts = mutableMapOf<String, BigDecimal>()
            currentAmounts.forEach { (balanceId, amount) ->
                val knownAmount = knownAmounts[balanceId]
                if (knownAmount == null || knownAmount.compareTo(amount) != 0) {
                    newAmounts[balanceId] = amount
                }
            }

            if (newAmounts.isNotEmpty()) {
                knownAmounts.putAll(newAmounts)
                amountChangesCallback(newAmounts)
            }
        } catch (e: Exception) {
            Logger.getGlobal().log(Level.WARNING, "Failed to check balances amounts (${e.message})")
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
    }
}