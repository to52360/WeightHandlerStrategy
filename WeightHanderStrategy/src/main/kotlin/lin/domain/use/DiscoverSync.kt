package lin.domain.use

import lin.domain.context.FourAnimationTime
import lin.domain.context.UseAnimationTime
import lin.myLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DiscoverSync {
    private val fallbackCount = AtomicInteger(0)
    private val activeLatch = AtomicReference<CountDownLatch?>()

    fun registerExpectedDiscover(): DiscoverTicket {
        val latch = CountDownLatch(1)
        activeLatch.set(latch)
        myLog.info { "注册发现等待" }
        return DiscoverTicket(latch) {
            activeLatch.compareAndSet(latch, null)
        }
    }

    fun onSdkChooseCompleted() {
        val latch = activeLatch.get()
        if (latch != null) {
            myLog.info { "唤醒发现等待" }
            latch.countDown()
        } else {
            myLog.info { "记录无上下文发现动作" }
            fallbackCount.incrementAndGet()
        }
    }

    fun waitFallbackIfNeeded() {
        if (fallbackCount.get() == 0) return
        myLog.info { "尝试等待发现动作" }
        Thread.sleep(FourAnimationTime)

        repeat(MAX_RETRIES) {
            Thread.sleep(UseAnimationTime)
            val result = fallbackCount.decrementAndGet()
            if (result <= 0) {
                if (result < 0) {
                    myLog.warn { "非法状态异常,预期之外的状态" }
                    fallbackCount.set(0)
                }
                return
            }
        }

        myLog.warn { "超次数设置为0" }
        fallbackCount.set(0)
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}

class DiscoverTicket(
    private val latch: CountDownLatch,
    private val closeAction: () -> Unit
) {
    fun await(timeoutMs: Long) {
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun close() {
        closeAction()
    }
}
