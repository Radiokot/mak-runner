package com.distributedlab.mak

import com.distributedlab.mak.api.Api
import com.distributedlab.mak.historyobserver.logic.PaymentEffectsFilter
import com.distributedlab.mak.historyobserver.model.Payment
import org.tokend.sdk.api.base.params.PagingOrder
import org.tokend.sdk.api.base.params.PagingParamsV2
import org.tokend.sdk.api.v3.history.params.ParticipantEffectsPageParams
import org.tokend.sdk.api.v3.history.params.ParticipantEffectsParams
import org.tokend.sdk.keyserver.KeyServer
import org.tokend.sdk.keyserver.models.WalletInfo
import org.tokend.sdk.signing.AccountRequestSigner
import org.tokend.sdk.utils.SimplePagedResourceLoader
import org.tokend.sdk.utils.extentions.encodeHexString
import org.tokend.wallet.Account
import org.tokend.wallet.TransactionBuilder
import org.tokend.wallet.utils.Hashing
import org.tokend.wallet.xdr.Fee
import org.tokend.wallet.xdr.Operation
import org.tokend.wallet.xdr.PaymentFeeData
import org.tokend.wallet.xdr.op_extensions.SimplePaymentOp
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

class Refunder(
    private val assetCode: String,
    private val assetReceiverEmail: String,
    private val assetReceiverPassword: CharArray,
    tokenDUrl: String
) {
    private lateinit var assetOwner: WalletInfo

    private lateinit var balanceId: String
    private var payments = listOf<Payment>()

    private val api: Api by lazy {
        Api(tokenDUrl, "", null, Config.HTTP_LOGS_ENABLED)
    }

    private val signedApi: Api by lazy {
        val assetOwnerAccount = Account.fromSecretSeed(assetOwner.secretSeed)
        Api(tokenDUrl, "", AccountRequestSigner(assetOwnerAccount), Config.HTTP_LOGS_ENABLED)
    }

    fun start(
        startDate: Date,
        ignoreMcIds: Set<String>
    ) {
        Logger.getGlobal().log(Level.INFO, "Start date is ${startDate.time / 1000L} ($startDate )")
        if (ignoreMcIds.isNotEmpty()) {
            Logger.getGlobal().log(Level.INFO, "Ignoring ${ignoreMcIds.joinToString()}")
        }

        Logger.getGlobal().log(Level.INFO, "Signing in...")
        signIn()

        obtainBalanceId()

        Logger.getGlobal().log(Level.INFO, "Collecting payments...")
        collectPayments(startDate)

        Logger.getGlobal().log(Level.INFO, "Preparing refund...")
        doRefundIfNeeded(startDate, ignoreMcIds)

        Logger.getGlobal().log(Level.INFO, "Success")
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

    private fun obtainBalanceId() {
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
    }

    private fun collectPayments(startDate: Date) {
        val filter = PaymentEffectsFilter(startDate, balanceId)

        val loader = SimplePagedResourceLoader({ nextCursor ->
            signedApi.v3.history
                .get(
                    ParticipantEffectsPageParams(
                        balance = balanceId,
                        include = listOf(
                            ParticipantEffectsParams.Includes.OPERATION,
                            ParticipantEffectsParams.Includes.OPERATION_DETAILS
                        ),
                        pagingParams = PagingParamsV2(
                            page = nextCursor,
                            limit = PAGE_LIMIT,
                            order = PagingOrder.ASC
                        )
                    )
                )
        })

        val items = try {
            loader.loadAll().execute().get()
        } catch (e: Exception) {
            throw IllegalStateException("Unable to fetch history", e)
        }

        payments = items
            .filter(filter::test)
            .mapNotNull(Payment.Companion::fromEffect)
    }

    private fun doRefundIfNeeded(
        startDate: Date,
        ignoreMcIds: Set<String>
    ) {
        val filteredOutPayments = mutableListOf<Payment>()

        val totalAmountByAccount = payments
            .filter { payment ->
                (!ignoreMcIds.contains(payment.referrer)).also { isAccepted ->
                    if (!isAccepted) {
                        filteredOutPayments.add(payment)
                    }
                }
            }
            .groupBy(Payment::sourceAccountId)
            .mapValues { (_, accountPayments) ->
                accountPayments.fold(BigDecimal.ZERO) { acc, payment ->
                    acc + payment.amount
                }
            }

        if (totalAmountByAccount.isEmpty()) {
            Logger.getGlobal().log(Level.INFO, "No payments collected, refund is not needed")
        }

        if (filteredOutPayments.isNotEmpty()) {
            Logger.getGlobal().log(Level.INFO, "Ignored ${filteredOutPayments.size} payments by McID")
        }

        val networkParams = try {
            api.general
                .getSystemInfo()
                .execute()
                .get()
                .toNetworkParams()
        } catch (e: Exception) {
            throw IllegalStateException("Unable to get network params", e)
        }

        val zeroFee = Fee(0, 0, Fee.FeeExt.EmptyVersion())

        val formattedDate = SimpleDateFormat("yyyy.MM.dd").format(startDate)

        val operations = totalAmountByAccount
            .map { (accountId, amount) ->
                val subject = "Refund for $formattedDate"
                val reference = (accountId + subject)
                    .toByteArray()
                    .let(Hashing::sha256)
                    .encodeHexString()
                    .substring(0, 32)

                SimplePaymentOp(
                    balanceId,
                    accountId,
                    networkParams.amountToPrecised(amount),
                    PaymentFeeData(zeroFee, zeroFee, false, PaymentFeeData.PaymentFeeDataExt.EmptyVersion()),
                    subject,
                    reference
                )
            }

        val transaction = TransactionBuilder(networkParams, assetOwner.accountId)
            .addOperations(operations.map(Operation.OperationBody::Payment))
            .addSigner(Account.fromSecretSeed(assetOwner.secretSeed))
            .build()

        Logger.getGlobal().log(Level.INFO, "Sending transaction with ${operations.size} operations")
        try {
            api.v3.transactions
                .submit(transaction, waitForIngest = true)
                .execute()
        } catch (e: Exception) {
            throw IllegalStateException("Unable to send transaction", e)
        }
    }

    private companion object {
        private const val PAGE_LIMIT = 40
    }
}