package cn.alittlecookie.lut2photo.lut2photo.ui.notifications

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.alittlecookie.lut2photo.lut2photo.R

class NotificationsViewModel(application: Application) : AndroidViewModel(application) {

    private val _text = MutableLiveData<String>().apply {
        value = getApplication<Application>().getString(R.string.settings)
    }
    val text: LiveData<String> = _text
}
