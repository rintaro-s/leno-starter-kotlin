package de.kai_morich.simple_bluetooth_terminal

internal object Constants {
    // values have to be globally unique
    const val INTENT_ACTION_DISCONNECT: String = BuildConfig.APPLICATION_ID + ".Disconnect"
    const val NOTIFICATION_CHANNEL: String = BuildConfig.APPLICATION_ID + ".Channel"
    const val INTENT_CLASS_MAIN_ACTIVITY: String = BuildConfig.APPLICATION_ID + ".MainActivity"

    // values have to be unique within each app
    const val NOTIFY_MANAGER_START_FOREGROUND_SERVICE: Int = 1001
}
