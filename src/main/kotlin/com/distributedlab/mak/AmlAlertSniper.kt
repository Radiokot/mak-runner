package com.distributedlab.mak

import com.distributedlab.mak.api.Api
import com.distributedlab.mak.balancesobserver.BalancesObserverDaemon
import com.distributedlab.mak.util.RunnableQueueDaemon
import org.tokend.sdk.api.base.params.PagingParamsV2
import org.tokend.sdk.api.integrations.dns.params.ClientsPageParams
import org.tokend.sdk.api.transactions.model.TransactionFailedException
import org.tokend.sdk.api.v3.accounts.params.AccountParamsV3
import org.tokend.sdk.keyserver.KeyServer
import org.tokend.sdk.keyserver.models.WalletInfo
import org.tokend.sdk.signing.AccountRequestSigner
import org.tokend.sdk.utils.SimplePagedResourceLoader
import org.tokend.wallet.Account
import org.tokend.wallet.NetworkParams
import org.tokend.wallet.PublicKeyFactory
import org.tokend.wallet.TransactionBuilder
import org.tokend.wallet.xdr.AMLAlertRequest
import org.tokend.wallet.xdr.CreateAMLAlertRequestOp
import org.tokend.wallet.xdr.Operation
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.absoluteValue
import kotlin.math.min

class AmlAlertSniper(
    private val assetCode: String,
    private val companyEmail: String,
    private val companyPassword: CharArray,
    masterSeed: CharArray,
    tokenDUrl: String
) {
    private val masterAccount = Account.fromSecretSeed(masterSeed)

    private lateinit var assetOwner: WalletInfo
    private lateinit var networkParams: NetworkParams

    private val balancesByEmails: MutableMap<String, String> = mutableMapOf()
    private val processingBalances: MutableSet<String> = mutableSetOf()
    private val processedBalances: MutableSet<String> = mutableSetOf()

    private val api: Api by lazy {
        Api(tokenDUrl, "", null, Config.HTTP_LOGS_ENABLED)
    }

    private val signedApi: Api by lazy {
        val assetOwnerAccount = Account.fromSecretSeed(assetOwner.secretSeed)
        Api(tokenDUrl, "", AccountRequestSigner(assetOwnerAccount), Config.HTTP_LOGS_ENABLED)
    }

    private val masterSignedApi: Api by lazy {
        Api(tokenDUrl, "", AccountRequestSigner(masterAccount), Config.HTTP_LOGS_ENABLED)
    }

    private val amlQueues = List(AML_QUEUES_COUNT) { RunnableQueueDaemon() }

    fun start(
        emails: Collection<String>,
        amount: BigDecimal,
        referenceBase: String
    ) {
        Logger.getGlobal().log(Level.INFO, "Signing in...")
        signIn()

        Logger.getGlobal().log(Level.INFO, "Obtaining balances...")
        obtainBalances(emails)

        Logger.getGlobal().log(Level.INFO, "Obtaining network params...")
        obtainNetworkParams()

        observeBalancesAsync(amount, referenceBase)
        Logger.getGlobal().log(Level.INFO, "Balances polling started")

        amlQueues.forEach(RunnableQueueDaemon::start)
        Logger.getGlobal().log(Level.INFO, "${amlQueues.size} AML queues started")

        CountDownLatch(1).await()
    }

    private fun signIn() {
        val keyServer = KeyServer(api.wallets)

        try {
            assetOwner = keyServer.getWalletInfo(
                companyEmail,
                companyPassword
            )
                .execute()
                .get()
        } catch (e: Exception) {
            throw IllegalStateException("Unable to sign in", e)
        }
    }

    private fun obtainBalances(emails: Collection<String>) {
        val clients = try {
            SimplePagedResourceLoader({ nextCursor ->
                signedApi.integrations.dns.getBusinessClients(
                    businessId = assetOwner.accountId,
                    params = ClientsPageParams(
                        pagingParams = PagingParamsV2(page = nextCursor),
                        include = listOf(ClientsPageParams.Includes.BALANCES)
                    )
                )
            })
                .loadAll()
                .execute()
                .get()
        } catch (e: Exception) {
            throw IllegalStateException("Unable to obtain balances", e)
        }

        balancesByEmails.putAll(
            emails
                .mapNotNull { email ->
                    val balanceId = clients
                        .find { client ->
                            client.email == email
                        }
                        ?.let { client ->
                            api.v3.accounts.getById(
                                accountId = client.accountId!!,
                                params = AccountParamsV3(
                                    include = listOf(AccountParamsV3.Includes.BALANCES)
                                )
                            )
                                .execute()
                                .get()
                        }
                        ?.balances
                        ?.find { balance ->
                            balance.asset.id == assetCode
                        }
                        ?.id
                        ?: return@mapNotNull null

                    email to balanceId
                }
        )

        emails.forEach { email ->
            if (!balancesByEmails.containsKey(email)) {
                Logger.getGlobal().log(Level.WARNING, "No balance ID found for $email")
            }
        }
    }

    private fun obtainNetworkParams() {
        networkParams = try {
            api.general.getSystemInfo().execute().get().toNetworkParams()
        } catch (e: Exception) {
            throw IllegalStateException("Unable to load network params", e)
        }
    }

    private fun observeBalancesAsync(
        amountToAml: BigDecimal,
        referenceBase: String
    ) {
        val callback: (Map<String, BigDecimal>) -> Unit = { newAmounts ->
            newAmounts.forEach { (balanceId, newAmount) ->
                if (newAmount >= amountToAml) {
                    enqueueAml(balanceId, amountToAml, referenceBase)
                }
            }
        }

        val balances = balancesByEmails.values.toList()

        for (rangeStart in 0 until balances.size step BALANCES_PER_OBSERVER) {
            val rangeEnd = min(rangeStart + BALANCES_PER_OBSERVER, balances.size)

            BalancesObserverDaemon(
                balances = balances.subList(rangeStart, rangeEnd),
                masterSignedApi = masterSignedApi,
                amountChangesCallback = callback
            ).start()
        }
    }

    private fun enqueueAml(
        balanceId: String,
        amountToAml: BigDecimal,
        referenceBase: String,
        withDelay: Boolean = false
    ) {
        if (processedBalances.contains(balanceId) || processedBalances.contains(balanceId)) {
            return
        }

        processingBalances.add(balanceId)

        enqueueToMinimalLoadedQueue {
            if (withDelay) {
                Thread.sleep(FAILED_AML_DELAY_MS)
            }

            val email = balancesByEmails
                .entries
                .first{ it.value == balanceId }
                .key

            Logger.getGlobal().log(Level.INFO, "Performing AML for ${amountToAml.toPlainString()} " +
                    "on $email")
            val successfullyProcessed = try {
                TransactionBuilder(networkParams, masterAccount.accountId)
                    .addSigner(masterAccount)
                    .addOperation(
                        Operation.OperationBody.CreateAmlAlert(
                            CreateAMLAlertRequestOp(
                                reference = "$referenceBase ${balanceId.hashCode().absoluteValue}",
                                allTasks = 0,
                                amlAlertRequest = AMLAlertRequest(
                                    balanceID = PublicKeyFactory.fromBalanceId(balanceId),
                                    amount = networkParams.amountToPrecised(amountToAml),
                                    creatorDetails = "{}",
                                    ext = AMLAlertRequest.AMLAlertRequestExt.EmptyVersion()
                                ),
                                ext = CreateAMLAlertRequestOp.CreateAMLAlertRequestOpExt.EmptyVersion()
                            )
                        )
                    )
                    .build()
                .also { api.v3.transactions.submit(it, waitForIngest = false).execute() }
                true
            } catch (e: Exception) {
                if (e is TransactionFailedException
                    && e.firstOperationResultCode == "op_reference_duplication"
                ) {
                    true
                } else {
                    Logger.getGlobal().log(
                        Level.WARNING,
                        "Unable to perform AML on $email (${e.message}), " +
                                "retry in $FAILED_AML_DELAY_MS ms"
                    )
                    false
                }
            } finally {
                processingBalances.remove(balanceId)
            }

            if (successfullyProcessed) {
                Logger.getGlobal().log(Level.INFO, "Successfully performed AML for " +
                        "${amountToAml.toPlainString()} " + "on $email")
                processedBalances.add(balanceId)
                logLeftEmails()
            } else {
                enqueueToMinimalLoadedQueue {
                    enqueueAml(balanceId, amountToAml, referenceBase, true)
                }
            }
        }
    }

    private fun enqueueToMinimalLoadedQueue(runnable: () -> Unit) {
        val queue = amlQueues.minBy(RunnableQueueDaemon::queueSize)
            ?: throw IllegalStateException("AML queues pool is empty")
        queue.enqueue(runnable)
    }

    private fun logLeftEmails() {
        val leftEmailsString = balancesByEmails
            .filterValues { balanceId ->
                !processedBalances.contains(balanceId)
            }
            .keys
            .joinToString()

        Logger.getGlobal().log(Level.INFO, "Left emails: $leftEmailsString")
    }

    private companion object {
        private const val BALANCES_PER_OBSERVER = 10
        private const val AML_QUEUES_COUNT = 10
        private const val FAILED_AML_DELAY_MS = 5000L
    }
}