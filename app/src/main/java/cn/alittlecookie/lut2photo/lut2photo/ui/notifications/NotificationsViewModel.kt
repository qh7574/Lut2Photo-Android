package cn.alittlecookie.lut2photo.lut2photo.ui.notifications

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class NotificationsViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "设置"
    }
    val text: LiveData<String> = _text
}
