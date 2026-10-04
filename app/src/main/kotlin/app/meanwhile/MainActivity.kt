package app.meanwhile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.meanwhile.ui.main.MainScreen
import app.meanwhile.ui.theme.MeanwhileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MeanwhileTheme {
                MainScreen()
            }
        }
    }
}
