package com.distributedlab.mak.api.helper

import com.distributedlab.mak.api.helper.model.SetFormUrlRequestBody
import com.distributedlab.mak.api.helper.model.SetPaymentStatesRequestBody
import com.distributedlab.mak.model.PaymentState
import org.tokend.sdk.api.base.ApiRequest
import org.tokend.sdk.api.base.SimpleRetrofitApiRequest

class MakHelperApi(
    private val helperService: MakHelperService,
    private val helperUrl: String
) {
    /**
     * Sets form URL of current Mak
     *
     * @param editableFormUrl form URL for editing (with /edit in the end)
     */
    fun setFormUrl(editableFormUrl: String): ApiRequest<Void> {
        return SimpleRetrofitApiRequest(
            helperService.setFormUrl(
                helperUrl,
                SetFormUrlRequestBody(editableFormUrl)
            )
        )
    }

    /**
     * Sets payment states in the response sheet
     *
     * @param editableSheetUrl form response sheet URL for editing (with /edit in the end)
     * @param states pairs of McID to payment state
     */
    fun setPaymentStates(
        editableSheetUrl: String,
        states: Collection<Pair<String, PaymentState>>
    ): ApiRequest<Void> {
        return SimpleRetrofitApiRequest(
            helperService.setPaymentStates(
                helperUrl,
                SetPaymentStatesRequestBody(
                    editableSheetUrl,
                    states.map {
                        SetPaymentStatesRequestBody.State(it.first, it.second.name)
                    }
                )
            )
        )
    }
}