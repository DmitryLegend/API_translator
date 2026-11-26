package com.dmitrylegend.apitranslator

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class RootTab { MENU, DEEPL, OVERLAY, THEME }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("podstrochnik", Context.MODE_PRIVATE) }

    var tab by remember { mutableStateOf(RootTab.MENU) }
    var accentArgb by remember { mutableStateOf(prefs.getInt("accent", 0xFF6750A4.toInt())) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
    var overlayEnabled by remember { mutableStateOf(prefs.getBoolean("overlay", true)) }
    var deepLKey by remember { mutableStateOf(prefs.getString("deepl_key", "") ?: "") }

    val accent = Color(accentArgb)
    val colorScheme = remember(accentArgb, dark) {
        if (dark) {
            darkColorScheme(
                primary = accent,
                secondary = Color(0xFF03DAC6),
                tertiary = Color(0xFFFFB74D),
                background = Color(0xFF1A1C1E),
                surface = Color(0xFF1A1C1E),
                surfaceVariant = Color(0xFF49454F)
            )
        } else {
            lightColorScheme(
                primary = accent,
                secondary = Color(0xFF0061A4),
                tertiary = Color(0xFF006D32),
                background = Color(0xFFFBF8EF),
                surface = Color(0xFFFBF8EF),
                surfaceVariant = Color(0xFFE7E0EC)
            )
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Scaffold(
            containerColor = colorScheme.background,
            bottomBar = { GlassBottomBar(selected = tab, onSelect = { tab = it }) }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (tab) {
                    RootTab.MENU -> MainScreen(onNavigate = { tab = it })
                    RootTab.DEEPL -> DeepLScreen(
                        key = deepLKey,
                        onKeyChange = { deepLKey = it },
                        onSave = { prefs.edit().putString("deepl_key", deepLKey).apply() }
                    )
                    RootTab.OVERLAY -> OverlayScreen(
                        enabled = overlayEnabled,
                        onEnabledChange = {
                            overlayEnabled = it
                            prefs.edit().putBoolean("overlay", it).apply()
                        }
                    )
                    RootTab.THEME -> ThemeScreen(
                        accent = accent,
                        onAccentChange = {
                            accentArgb = it
                            prefs.edit().putInt("accent", it).apply()
                        },
                        dark = dark,
                        onDarkChange = {
                            dark = it
                            prefs.edit().putBoolean("dark", it).apply()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun GlassBottomBar(selected: RootTab, onSelect: (RootTab) -> Unit) {
    NavigationBar(
        containerColor = Color(0xFF000000).copy(alpha = 0.65f),
        contentColor = Color.White,
        modifier = Modifier.navigationBarsPadding()
    ) {
        NavigationBarItem(
            selected = selected == RootTab.MENU,
            onClick = { onSelect(RootTab.MENU) },
            icon = { Icon(Icons.Filled.Menu, contentDescription = "Главное меню") },
            label = { Text("Меню") }
        )
        NavigationBarItem(
            selected = selected == RootTab.DEEPL,
            onClick = { onSelect(RootTab.DEEPL) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = "DeepL") },
            label = { Text("DeepL") }
        )
        NavigationBarItem(
            selected = selected == RootTab.OVERLAY,
            onClick = { onSelect(RootTab.OVERLAY) },
            icon = { Icon(Icons.Filled.Movie, contentDescription = "Оверлей") },
            label = { Text("Оверлей") }
        )
        NavigationBarItem(
            selected = selected == RootTab.THEME,
            onClick = { onSelect(RootTab.THEME) },
            icon = { Icon(Icons.Filled.Palette, contentDescription = "Тема") },
            label = { Text("Тема") }
        )
    }
}

@Composable
fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = { content() }
    )
}

@Composable
fun MainScreen(onNavigate: (RootTab) -> Unit) {
    ScreenColumn {
        Spacer(Modifier.height(8.dp))
        Text("Подстрочник", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Перевод того, что сейчас на экране",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onNavigate(RootTab.DEEPL) }, modifier = Modifier.fillMaxWidth()) {
            Text("Настройки DeepL")
        }
        OutlinedButton(onClick = { onNavigate(RootTab.OVERLAY) }, modifier = Modifier.fillMaxWidth()) {
            Text("Настройки оверлея")
        }
        OutlinedButton(onClick = { onNavigate(RootTab.THEME) }, modifier = Modifier.fillMaxWidth()) {
            Text("Тема и Material You")
        }
    }
}

@Composable
fun DeepLScreen(key: String, onKeyChange: (String) -> Unit, onSave: () -> Unit) {
    ScreenColumn {
        Text("DeepL", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = key,
            onValueChange = onKeyChange,
            label = { Text("API-ключ DeepL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("Сохранить")
        }
    }
}

@Composable
fun OverlayScreen(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    ScreenColumn {
        Text("Оверлей", style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Перевод включён")
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
fun ThemeScreen(accent: Color, onAccentChange: (Int) -> Unit, dark: Boolean, onDarkChange: (Boolean) -> Unit) {
    val swatches = listOf(
        Color(0xFF6750A4) to "Фиолетовый",
        Color(0xFF0061A4) to "Синий",
        Color(0xFF006D32) to "Зелёный",
        Color(0xFFC2185B) to "Розовый",
        Color(0xFF8A4B00) to "Оранжевый"
    )
    ScreenColumn {
        Text("Тема", style = MaterialTheme.typography.headlineMedium)
        Text("Акцентный цвет (Material You)", style = MaterialTheme.typography.bodyMedium)
        swatches.forEach { (color, name) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAccentChange(color.toArgb()) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color, CircleShape)
                        .then(if (color == accent) Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier)
                )
                Text(name)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Тёмная тема")
            Switch(checked = dark, onCheckedChange = onDarkChange)
        }
    }
}
