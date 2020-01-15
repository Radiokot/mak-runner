package com.distributedlab.mak

import java.util.function.Predicate

object Config {
    const val TOKEND_API_URL = "https://api.staging.conto.me"
    const val ASSET_CODE = "6DEB5D"
    const val ASSET_RECEIVER_EMAIL = "ole@mail.com"
    const val HTTP_LOGS_ENABLED = false

    val PAYMENT_REFERRER_PREDICATE = Predicate { referrer: String ->
        Regex("^[a-f|0-9]{16}").matches(referrer)
    }
}