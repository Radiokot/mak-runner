package com.distributedlab.mak

import com.distributedlab.mak.api.Api
import com.distributedlab.mak.historyobserver.HistoryObserverDaemon
import com.distributedlab.mak.historyobserver.model.Payment
import com.distributedlab.mak.model.PaymentState
import com.distributedlab.mak.util.RunnableQueueDaemon
import org.tokend.sdk.api.transactions.model.TransactionFailedException
import org.tokend.sdk.keyserver.KeyServer
import org.tokend.sdk.keyserver.models.WalletInfo
import org.tokend.sdk.signing.AccountRequestSigner
import org.tokend.wallet.Account
import org.tokend.wallet.NetworkParams
import org.tokend.wallet.TransactionBuilder
import org.tokend.wallet.xdr.Fee
import org.tokend.wallet.xdr.Operation
import org.tokend.wallet.xdr.PaymentFeeData
import org.tokend.wallet.xdr.op_extensions.SimplePaymentOp
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
    private lateinit var balanceId: String

    private val api: Api by lazy {
        Api(tokenDUrl, helperUrl, null, Config.HTTP_LOGS_ENABLED)
    }

    private val signedApi: Api by lazy {
        val assetOwnerAccount = Account.fromSecretSeed(assetOwner.secretSeed)
        Api(tokenDUrl, helperUrl, AccountRequestSigner(assetOwnerAccount), Config.HTTP_LOGS_ENABLED)
    }

    private val paidAmountsByReferrer = mutableMapOf<String, BigDecimal>()
    private val paymentStateUpdatesQueueDaemon = RunnableQueueDaemon()

    private val invalidPaymentsToRefund = mutableSetOf<Payment>()
    private val invalidPaymentsRefundQueueDaemon = RunnableQueueDaemon()

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

        invalidPaymentsRefundQueueDaemon.start()
        Logger.getGlobal().log(Level.INFO, "Invalid payments refund queue started")

        updatePaymentStatesSometimes()
        refundInvalidPaymentsSometimes()

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

        balanceId = balances
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

            if (VALID_REFERRER_REGEX.matches(referrer)) {
                val alreadyPaidAmount = paidAmountsByReferrer[referrer] ?: BigDecimal.ZERO
                val newAmount = alreadyPaidAmount + payment.amount
                paidAmountsByReferrer[referrer] = newAmount

                Logger.getGlobal().log(Level.INFO, "Received $newAmount total from $referrer")
            } else {
                invalidPaymentsToRefund.add(payment)

                Logger.getGlobal().log(
                    Level.WARNING, "Received ${payment.amount} with " +
                            "invalid referrer $referrer"
                )
            }
        }

        enqueuePaymentStatesUpdate()
        enqueueInvalidPaymentsRefund()
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
                Logger.getGlobal().log(Level.INFO, "Payment states updated")
            } catch (e: Exception) {
                Logger.getGlobal().log(Level.WARNING, "Payment states update failed")
            }
        }
    }

    private fun enqueueInvalidPaymentsRefund() {
        invalidPaymentsRefundQueueDaemon.enqueue {
            if (invalidPaymentsToRefund.isEmpty()) {
                return@enqueue
            }

            val networkParams = try {
                api.general
                    .getSystemInfo()
                    .execute()
                    .get()
                    .toNetworkParams()
            } catch (e: Exception) {
                Logger.getGlobal().log(Level.WARNING, "Unable to load network info")
                return@enqueue
            }

            val refundedPayments = mutableSetOf<Payment>()

            invalidPaymentsToRefund.forEach { payment ->
                try {
                    refundInvalidPayment(payment, networkParams)
                    refundedPayments.add(payment)
                } catch (e: Exception) {
                    Logger.getGlobal().log(
                        Level.WARNING, "Unable to refund " +
                                "payment from ${payment.referrer}"
                    )
                }
            }

            Logger.getGlobal().log(
                Level.INFO, "${refundedPayments.size} invalid payments " +
                        "refunded"
            )

            invalidPaymentsToRefund.removeAll(refundedPayments)
        }
    }

    private fun refundInvalidPayment(
        payment: Payment,
        networkParams: NetworkParams
    ) {
        val zeroFee = Fee(0, 0, Fee.FeeExt.EmptyVersion())

        val op = SimplePaymentOp(
            sourceBalanceId = balanceId,
            destAccountId = payment.sourceAccountId,
            amount = networkParams.amountToPrecised(payment.amount),
            subject = "\"${payment.referrer}\" is not a valid McID \uD83D\uDE20",
            reference = "REFUND${payment.id}",
            feeData = PaymentFeeData(
                zeroFee, zeroFee, false,
                PaymentFeeData.PaymentFeeDataExt.EmptyVersion()
            )
        )

        val tx = TransactionBuilder(networkParams, assetOwner.accountId)
            .addOperation(Operation.OperationBody.Payment(op))
            .addSigner(Account.fromSecretSeed(assetOwner.secretSeed))
            .build()

        try {
            api.v3.transactions
                .submit(tx, waitForIngest = false)
                .execute()

        } catch (e: TransactionFailedException) {
            if (e.firstOperationResultCode != "op_reference_duplication") {
                throw e
            }
        }
    }

    private fun updatePaymentStatesSometimes() {
        val task = object : TimerTask() {
            override fun run() {
                enqueuePaymentStatesUpdate()
            }
        }

        val timer = Timer(true)

        timer.schedule(task, PAYMENT_STATES_UPDATE_INTERVAL_MS, PAYMENT_STATES_UPDATE_INTERVAL_MS)
    }

    private fun refundInvalidPaymentsSometimes() {
        val task = object : TimerTask() {
            override fun run() {
                enqueueInvalidPaymentsRefund()
            }
        }

        val timer = Timer(true)

        timer.schedule(task, INVALID_PAYMENTS_REFUND_INTERVAL_MS, INVALID_PAYMENTS_REFUND_INTERVAL_MS)
    }

    private companion object {
        private const val PAYMENT_STATES_UPDATE_INTERVAL_MS = 30000L
        private const val INVALID_PAYMENTS_REFUND_INTERVAL_MS = 30000L
        private val VALID_REFERRER_REGEX = Regex("^[a-f|0-9]{16}")
    }
}