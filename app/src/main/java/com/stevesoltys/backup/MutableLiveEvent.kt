package com.stevesoltys.backup

class MutableLiveEvent<T> : LiveEvent<T>() {

    fun postEvent(value: T) {
        super.postValue(ConsumableEvent(value))
    }

    fun setEvent(value: T) {
        super.setValue(ConsumableEvent(value))
    }

}
