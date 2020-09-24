package com.stevesoltys.seedvault.ui

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.stevesoltys.seedvault.ui.LiveEvent.ConsumableEvent

open class LiveEvent<T> : LiveData<ConsumableEvent<T>>() {

    fun observeEvent(owner: LifecycleOwner, handler: LiveEventHandler<in T>) {
        val observer = LiveEventObserver(handler)
        super.observe(owner, observer)
    }

    class ConsumableEvent<T>(private val content: T) {
        private var consumed = false

        val contentIfNotConsumed: T?
            get() {
                if (consumed) return null
                consumed = true
                return content
            }
    }

    internal class LiveEventObserver<T>(private val handler: LiveEventHandler<in T>) :
        Observer<ConsumableEvent<T>> {
        override fun onChanged(consumableEvent: ConsumableEvent<T>?) {
            if (consumableEvent != null) {
                val content = consumableEvent.contentIfNotConsumed
                if (content != null) handler.onEvent(content)
            }
        }
    }

}
