package com.distributedlab.mak

import com.distributedlab.mak.api.Api
import com.distributedlab.mak.historyobserver.HistoryObserverDaemon
import com.distributedlab.mak.historyobserver.model.Payment
import com.distributedlab.mak.model.PaymentState
import com.distributedlab.mak.util.RunnableQueueDaemon
import org.tokend.sdk.keyserver.KeyServer
import org.tokend.sdk.keyserver.models.WalletInfo
import org.tokend.sdk.signing.AccountRequestSigner
import org.tokend.wallet.Account
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.logging.Level
import java.util.logging.Logger

class Runner(
    private val assetCode: String,
    private val assetReceiverEmail: String,
    private val assetReceiverPassword: CharArray,
    private val requiredPaymentAmount: BigDecimal,
    private val editableFormUrl: String,
    private val editableSheetUrl: String,
    tokenDUrl: String,
    helperUrl: String
) {
    private lateinit var assetOwner: WalletInfo

    private val api: Api by lazy {
        Api(tokenDUrl, helperUrl, null, Config.HTTP_LOGS_ENABLED)
    }

    private val signedApi: Api by lazy {
        val assetOwnerAccount = Account.fromSecretSeed(assetOwner.secretSeed)
        Api(tokenDUrl, helperUrl, AccountRequestSigner(assetOwnerAccount), Config.HTTP_LOGS_ENABLED)
    }

    private val paidAmountsByReferrer = mutableMapOf<String, BigDecimal>()
    private val paymentStateUpdatesQueueDaemon = RunnableQueueDaemon()

    fun start(
        startDate: Date
    ) {
        Logger.getGlobal().log(Level.INFO, "Start date is ${startDate.time / 1000L} ($startDate )")

        Logger.getGlobal().log(Level.INFO, "Setting form URL...")
        setForm(editableFormUrl)

        Logger.getGlobal().log(Level.INFO, "Signing in...")
        signIn()

        observeHistoryAsync(startDate)
        Logger.getGlobal().log(Level.INFO, "History polling started")

        enqueuePaymentStatesUpdate()
        paymentStateUpdatesQueueDaemon.start()
        Logger.getGlobal().log(Level.INFO, "Payment state updates queue started")

        CountDownLatch(1).await()
    }

    private fun setForm(editableFormUrl: String) {
        try {
            api
                .makHelper
                .setFormUrl(editableFormUrl)
                .execute()
        } catch (e: Exception) {
            throw IllegalStateException("Unable to set form URL", e)
        }
    }

    private fun signIn() {
        val keyServer = KeyServer(api.wallets)

        try {
            assetOwner = keyServer.getWalletInfo(
                assetReceiverEmail,
                assetReceiverPassword
            )
                .execute()
                .get()
        } catch (e: Exception) {
            throw IllegalStateException("Unable to sign in", e)
        }
    }

    private fun observeHistoryAsync(startDate: Date) {
        val balances = try {
            signedApi.v3.accounts
                .getBalances(assetOwner.accountId)
                .execute()
                .get()
        } catch (e: Exception) {
            throw IllegalStateException("Unable to get balances", e)
        }

        val balanceId = balances
            .find { it.asset.id == assetCode }
            ?.id
            ?: throw IllegalStateException("No balance found for asset $assetCode")

        HistoryObserverDaemon(
            balanceId,
            startDate,
            signedApi.v3.history,
            this::onNewPayments
        )
            .start()
    }

    private fun onNewPayments(newPayments: Collection<Payment>) {
        newPayments.forEach { payment ->
            val referrer = payment.referrer
            val alreadyPaidAmount = paidAmountsByReferrer[referrer] ?: BigDecimal.ZERO
            val newAmount = alreadyPaidAmount + payment.amount
            paidAmountsByReferrer[referrer] = newAmount

            Logger.getGlobal().log(Level.INFO, "Received $newAmount total from $referrer")
        }

        enqueuePaymentStatesUpdate()
    }

    private fun enqueuePaymentStatesUpdate() {
        val states = paidAmountsByReferrer
            .mapValues { (_, amount) ->
                if (amount >= requiredPaymentAmount)
                    PaymentState.OK_ACCEPTED
                else
                    PaymentState.NOT_ENOUGH
            }
            .map { it.toPair() }

        paymentStateUpdatesQueueDaemon.enqueue {
            try {
                api.makHelper
                    .setPaymentStates(editableSheetUrl, states)
                    .execute()
            } catch (e: Exception) {
                Logger.getGlobal().log(Level.WARNING, "Payment states update failed")
            }
        }
    }
}