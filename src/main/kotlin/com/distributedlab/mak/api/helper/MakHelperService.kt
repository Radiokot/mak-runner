package com.distributedlab.mak.api.helper

import com.distributedlab.mak.api.helper.model.SetFormUrlRequestBody
import com.distributedlab.mak.api.helper.model.SetPaymentStatesRequestBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface MakHelperService {
    @POST
    fun setFormUrl(
        @Url helperUrl: String,
        @Body body: SetFormUrlRequestBody
    ): Call<Void>

    @POST
    fun setPaymentStates(
        @Url helperUrl: String,
        @Body body: SetPaymentStatesRequestBody
    ): Call<Void>
}