package xyz.hidasy.pebblepinmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import xyz.hidasy.pebblepinmanager.ui.PinComposerRoute
import xyz.hidasy.pebblepinmanager.ui.PinComposerViewModel
import xyz.hidasy.pebblepinmanager.ui.PinComposerViewModelFactory
import xyz.hidasy.pebblepinmanager.ui.theme.PebblePinManagerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PinComposerViewModel by viewModels {
        val container = (application as PebblePinManagerApp).container
        PinComposerViewModelFactory(
            pinSender = container.pinSender,
            repository = container.sentPinRepository,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PebblePinManagerTheme {
                PinComposerRoute(viewModel = viewModel)
            }
        }
    }
}
