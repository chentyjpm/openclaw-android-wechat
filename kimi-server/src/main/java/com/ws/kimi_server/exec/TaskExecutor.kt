package com.ws.kimi_server.exec

import java.util.concurrent.ConcurrentLinkedQueue

class TaskExecutor {
    private val queue = ConcurrentLinkedQueue<String>() // TODO replace with real task type

    fun enqueue(task: String) {
        queue.add(task)
    }
}
