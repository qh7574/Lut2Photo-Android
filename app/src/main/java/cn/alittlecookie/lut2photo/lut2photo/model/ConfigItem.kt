package cn.alittlecookie.lut2photo.lut2photo.model

/**
 * 相机配置项
 * @param name 配置项名称（内部标识）
 * @param label 配置项标签（显示名称）
 * @param type 配置项类型
 * @param currentValue 当前值
 * @param choices 可选值列表（对于 RADIO 类型）
 * @param min 最小值（对于 RANGE 类型）
 * @param max 最大值（对于 RANGE 类型）
 * @param step 步进值（对于 RANGE 类型）
 */
data class ConfigItem(
    val name: String,
    val label: String,
    val type: Int,
    val currentValue: String,
    val choices: Array<String>? = null,
    val min: Float = 0f,
    val max: Float = 0f,
    val step: Float = 0f
) {
    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_RANGE = 1
        const val TYPE_TOGGLE = 2
        const val TYPE_RADIO = 3
        const val TYPE_MENU = 4
        const val TYPE_BUTTON = 5
        const val TYPE_DATE = 6
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConfigItem

        if (name != other.name) return false
        if (label != other.label) return false
        if (type != other.type) return false
        if (currentValue != other.currentValue) return false
        if (choices != null) {
            if (other.choices == null) return false
            if (!choices.contentEquals(other.choices)) return false
        } else if (other.choices != null) return false
        if (min != other.min) return false
        if (max != other.max) return false
        if (step != other.step) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + type
        result = 31 * result + currentValue.hashCode()
        result = 31 * result + (choices?.contentHashCode() ?: 0)
        result = 31 * result + min.hashCode()
        result = 31 * result + max.hashCode()
        result = 31 * result + step.hashCode()
        return result
    }
}
