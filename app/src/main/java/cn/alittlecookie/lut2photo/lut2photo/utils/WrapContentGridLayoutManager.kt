package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.util.Log
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class WrapContentGridLayoutManager(
    context: Context,
    spanCount: Int
) : GridLayoutManager(context, spanCount) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            Log.e("WrapContentGridLayoutManager", "IndexOutOfBoundsException in RecyclerView", e)
        }
    }

    override fun supportsPredictiveItemAnimations(): Boolean {
        return false
    }
}