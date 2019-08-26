package com.distributedlab.mak.api.helper.model

import com.google.gson.annotations.SerializedName

class SetFormUrlRequestBody(
    @SerializedName("editable_form_url")
    val editableFormUrl: String
)