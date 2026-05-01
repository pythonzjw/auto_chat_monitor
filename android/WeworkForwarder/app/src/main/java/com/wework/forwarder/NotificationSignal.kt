package com.wework.forwarder

import kotlinx.coroutines.channels.Channel

/**
 * 通知信号通道
 *
 * WeWorkNotificationListener 发信号，CollectorService 主循环接收。
 * CONFLATED channel 只保留最新一条信号，多条通知合并为一次唤醒。
 */
object NotificationSignal {

    private val channel = Channel<String>(Channel.CONFLATED)

    fun emit(groupName: String) {
        channel.trySend(groupName)
    }

    suspend fun await(): String {
        return channel.receive()
    }

    fun drain() {
        while (channel.tryReceive().isSuccess) { /* 丢弃 */ }
    }
}
