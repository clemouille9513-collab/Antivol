package com.antivol.app

import android.app.admin.DeviceAdminReceiver

/** Permet à l'appli d'être "administrateur de l'appareil" pour pouvoir verrouiller l'écran. */
class AdminReceiver : DeviceAdminReceiver()
