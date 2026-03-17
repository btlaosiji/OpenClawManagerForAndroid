package com.singxie.openclawmanager

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.singxie.openclawmanager.di.MainViewModelFactory
import com.singxie.openclawmanager.ui.MainScreen
import com.singxie.openclawmanager.ui.MainViewModel
import com.singxie.openclawmanager.ui.theme.OpenClawManagerTheme

/** 崩溃时用于日志的最近状态（由 MainScreen 更新）。 */
object CrashLogState {
    var lastSelectedTab: Int = 0
    var chatMessagesSize: Int = 0
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception: ${throwable.javaClass.simpleName} ${throwable.message}", throwable)
            if (throwable is ArrayIndexOutOfBoundsException) {
                Log.e(TAG, "ArrayIndexOutOfBounds: length/index in message; lastSelectedTab=${CrashLogState.lastSelectedTab}, chatMessagesSize=${CrashLogState.chatMessagesSize}")
                Log.e(TAG, "Stack: ${throwable.stackTraceToString().take(800)}")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        enableEdgeToEdge()
        setContent {
            OpenClawManagerTheme {
                val viewModel: MainViewModel = viewModel(
                    factory = MainViewModelFactory(application)
                )
                MainScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    companion object {
        private const val TAG = "OpenClawCrash"
    }
}