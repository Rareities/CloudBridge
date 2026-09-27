package ca.pkay.rcloneexplorer.rclone

import ca.pkay.rcloneexplorer.util.FLog
import de.schuelken.cloudbridge.extensions.tag
import org.json.JSONObject
import java.util.Objects

class ProviderOption {

    var name: String = ""
    var help: String = ""
    var provider: String = ""
    var default: String = ""
    var value: Objects? = null
    var examples: ArrayList<OptionExampleItem> = ArrayList()
    var shortOpt: String = ""
    var hide: Int = 0
    var required: Boolean = false
    var isPassword: Boolean = false
    var noPrefix: Boolean = false
    var advanced: Boolean = false
    var exclusive: Boolean = false
    var defaultStr: String = ""
    var valueStr: String = ""
    var type: String = ""

    /** rclone's Option.Hide bit that excludes an option from config UIs. */
    fun isHiddenFromConfigurator(): Boolean = hide and HIDE_CONFIGURATOR != 0

    companion object {
        // Keep in sync with rclone/fs/registry.go OptionHideCommandLine and
        // OptionHideConfigurator. Hide is a bitmask, not a boolean.
        const val HIDE_COMMAND_LINE = 1
        const val HIDE_CONFIGURATOR = 1 shl 1

        fun newInstance(data: JSONObject): ProviderOption? {

            try {
                val item = ProviderOption()

                item.name = data.optString("Name")
                item.help = data.optString("Help")
                item.provider = data.optString("Type")
                val defaultValue = data.opt("Default")
                item.default = if (defaultValue == null || defaultValue == JSONObject.NULL) {
                    ""
                } else {
                    defaultValue.toString()
                }
                //item.value = data.get("Value")
                item.shortOpt = data.optString("ShortOpt")
                item.hide = data.optInt("Hide")
                item.required = data.optBoolean("Required")
                item.isPassword = data.optBoolean("IsPassword")
                item.noPrefix = data.optBoolean("NoPrefix")
                item.advanced = data.optBoolean("Advanced")
                item.exclusive = data.optBoolean("Exclusive")
                item.defaultStr = data.optString("DefaultStr")
                item.valueStr = data.optString("ValueStr")
                item.type = data.optString("Type")

                val examples = data.optJSONArray("Examples")
                if (examples != null) {
                    for (i in 0 until examples.length()) {
                        item.examples.add(OptionExampleItem(
                            examples.getJSONObject(i).optString("Value"),
                            examples.getJSONObject(i).optString("Help"),
                            examples.getJSONObject(i).optString("Provider")
                        ))
                    }
                }

                return item
            } catch (e: Exception) {
                // Provider metadata may include values. Never log the JSON body.
                FLog.e(tag(), "Unable to parse provider option metadata")
            }

            return null
        }
    }


    fun getNameCapitalized(): String {
        var tempName = name
        var wasSpace = false
        var capitalized = tempName[0].uppercaseChar().toString()

        for(s in tempName.drop(1)){
            if(wasSpace){
                capitalized += s.uppercaseChar()
            } else {
                capitalized += if(s == '_') ' ' else s
            }
            wasSpace = s == '_'
        }

        return capitalized
    }


}
