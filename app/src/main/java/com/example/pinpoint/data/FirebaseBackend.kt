package com.example.pinpoint.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.example.pinpoint.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage

class FirebaseBackend private constructor(app: FirebaseApp) {
    val auth: FirebaseAuth = FirebaseAuth.getInstance(app)
    val database: FirebaseFirestore = FirebaseFirestore.getInstance(app)
    val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance(app) }
    val functions: FirebaseFunctions = FirebaseFunctions.getInstance(app, "asia-southeast1")

    init {
        if (BuildConfig.DEBUG && BuildConfig.FIREBASE_EMULATORS) {
            val host = BuildConfig.FIREBASE_EMULATOR_HOST
            auth.useEmulator(host, 9099)
            database.useEmulator(host, 8080)
            storage.useEmulator(host, 9199)
            functions.useEmulator(host, 5001)
        }
    }

    companion object {
        @Volatile private var instance: FirebaseBackend? = null
        fun create(context: Context): FirebaseBackend? {
            if (BuildConfig.DEBUG && BuildConfig.FIREBASE_EMULATORS && FirebaseApp.getApps(context).none { it.name == "pinpoint-emulator" }) {
                FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                    .setProjectId("demo-pinpoint")
                    .setApplicationId("1:1234567890:android:pinpointdemo")
                    .setApiKey("AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                    .setStorageBucket("demo-pinpoint.appspot.com").build(), "pinpoint-emulator")
            }
            val app = if (BuildConfig.DEBUG && BuildConfig.FIREBASE_EMULATORS) {
                FirebaseApp.getInstance("pinpoint-emulator")
            } else {
                FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                    ?: FirebaseApp.initializeApp(context) ?: return null
            }
            return instance ?: synchronized(this) {
                instance ?: FirebaseBackend(app).also { instance = it }
            }
        }
    }
}
