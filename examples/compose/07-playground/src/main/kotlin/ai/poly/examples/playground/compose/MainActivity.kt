// Copyright PolyAI Limited

package ai.poly.examples.playground.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Rung 07 — the developer playground. `MainActivity` hosts [AppRoot]
 * (connect → loading → chat → error, plus the dev surfaces).
 */
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Expose testTags as view resource-ids so black-box UI Automator flow tests
                // can find them.
                Surface(Modifier.semantics { testTagsAsResourceId = true }) { AppRoot() }
            }
        }
    }
}
