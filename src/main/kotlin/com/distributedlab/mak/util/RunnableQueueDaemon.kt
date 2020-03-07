package com.distributedlab.mak.util

import java.util.*
import java.util.concurrent.CountDownLatch

class RunnableQueueDaemon : Thread() {
    init {
        isDaemon = true
    }

    private val queue = LinkedList<() -> Unit>()
    private var latch = CountDownLatch(1)

    val queueSize: Int
        get() = queue.size

    override fun run() {
        while (!isInterrupted) {
            latch.await()

            while (queue.isNotEmpty() && !isInterrupted) {
                queue.pop().invoke()
            }

            resetLatch()
        }
    }

    fun enqueue(runnable: () -> Unit) = synchronized(this) {
        queue.add(runnable)
        latch.countDown()
    }

    private fun resetLatch() = synchronized(this) {
        latch = CountDownLatch(1)
    }
}