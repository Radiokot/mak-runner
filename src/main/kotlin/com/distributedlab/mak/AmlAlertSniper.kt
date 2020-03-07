package com.distributedlab.mak

import com.distributedlab.mak.api.Api
import com.distributedlab.mak.balancesobserver.BalancesObserverDaemon
import org.tokend.sdk.api.base.params.PagingParamsV2
import org.tokend.sdk.api.integrations.dns.params.ClientsPageParams
import org.tokend.sdk.api.v3.accounts.params.AccountParamsV3
import org.tokend.sdk.keyserver.KeyServer
import org.tokend.sdk.keyserver.models.WalletInfo
import org.tokend.sdk.signing.AccountRequestSigner
import org.tokend.sdk.utils.SimplePagedResourceLoader
import org.tokend.wallet.Account
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.min

class AmlAlertSniper(
    private val assetCode: String,
    private val companyEmail: String,
    private val companyPassword: CharArray,
    masterSeed: CharArray,
    tokenDUrl: String
) {
    private lateinit var assetOwner: WalletInfo

    private val balancesByEmails: MutableMap<String, String> = mutableMapOf()

    private val api: Api by lazy {
        Api(tokenDUrl, "", null, Config.HTTP_LOGS_ENABLED)
    }

    private val signedApi: Api by lazy {
        val assetOwnerAccount = Account.fromSecretSeed(assetOwner.secretSeed)
        Api(tokenDUrl, "", AccountRequestSigner(assetOwnerAccount), Config.HTTP_LOGS_ENABLED)
    }

    private val masterSignedApi: Api by lazy {
        val masterAccount = Account.fromSecretSeed(masterSeed)
        Api(tokenDUrl, "", AccountRequestSigner(masterAccount), Config.HTTP_LOGS_ENABLED)
    }

    fun start(
        emails: Collection<String>,
        amount: BigDecimal
    ) {
        Logger.getGlobal().log(Level.INFO, "Signing in...")
        signIn()

        Logger.getGlobal().log(Level.INFO, "Obtaining balances...")
        obtainBalances(emails)

        observeBalancesAsync()
        Logger.getGlobal().log(Level.INFO, "Balances polling started")

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

        balancesByEmails.clear()
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

    private fun observeBalancesAsync() {
        val callback: (Map<String, BigDecimal>) -> Unit = { newAmounts ->
            println("Update:")
            newAmounts
                .filter { it.value.signum() > 0 }
                .forEach {
                    println("${it.key} - ${it.value}")
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

    private companion object {
        private const val BALANCES_PER_OBSERVER = 10
    }
}