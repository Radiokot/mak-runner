package com.distributedlab.mak.api

import com.distributedlab.mak.api.helper.MakHelperApi
import com.distributedlab.mak.api.helper.MakHelperService
import org.tokend.sdk.api.TokenDApi
import org.tokend.sdk.signing.RequestSigner

class Api(
    tokenDRootUrl: String,
    makHelperUrl: String,
    requestSigner: RequestSigner?,
    withLogs: Boolean
) : TokenDApi(tokenDRootUrl, requestSigner, withLogs = withLogs) {
    val makHelper: MakHelperApi by lazy {
        MakHelperApi(getService(MakHelperService::class.java), makHelperUrl)
    }
}