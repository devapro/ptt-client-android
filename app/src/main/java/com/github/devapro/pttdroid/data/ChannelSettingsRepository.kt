package com.github.devapro.pttdroid.data

class ChannelSettingsRepository(
    private val prefManager: PrefManager
) {

    fun getChannel(): Int {
        return prefManager.getInt("channel", 1)
    }

    fun setChannel(channel: Int) {
        prefManager.putInt("channel", channel)
    }
}