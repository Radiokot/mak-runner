package com.distributedlab.mak.api.helper.model

import com.google.gson.annotations.SerializedName

class SetPaymentStatesRequestBody(
    @SerializedName("editable_sheet_url")
    val editableSheetUrl: String,
    @SerializedName("states")
    val states: Collection<State>
) {
    class State(
        @SerializedName("mc_id")
        val mcId: String,
        @SerializedName("state")
        val state: String
    )
}