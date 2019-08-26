package com.distributedlab.mak.historyobserver.model

import java.math.BigDecimal

/**
 * Mak-related payment - payment OP of Mak asset with filled subject.
 */
class Payment(
    val id: String,
    val amount: BigDecimal,
    val referrer: String
) {
    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other is Payment && other.id == this.id
    }
}