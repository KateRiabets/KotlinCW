package com.example.phonewebcam

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecInfo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ArrayBlockingQueue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.phonewebcam.ui.theme.NunitoFontFamily
import com.example.phonewebcam.ui.theme.PhoneWebcamTheme
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.onFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.withContext


class MainActivity : ComponentActivity() {
        private lateinit var connectionManager: ConnectionManager //Керування з'єднанням
        private lateinit var audioManager: AudioManager //Керування аудіо
        private lateinit var cameraManager: CameraManager //Керування камерою

        companion object {
            const val TAG = "USB_TETHERING_CHECK"
        }

        override fun onCreate(savedInstanceState: Bundle?) { //При створенні активності
            super.onCreate(savedInstanceState)

            // Створення екземплярів менеджерів
            connectionManager = ConnectionManager()
            audioManager = AudioManager(this)
            cameraManager = CameraManager()

            // Перевірка та запит дозволу на використання камери
            val requestCameraPermission = registerForActivityResult(
                ActivityResultContracts.RequestPermission() // Контракт для запиту дозволу
            ) { granted ->
                if (!granted) {//Якщо дозвіл не надано
                    Log.e("MainActivity", "Camera permission not granted")
                }
            }
            // Якщо дозвіл не надано, запитуємо його
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermission.launch(Manifest.permission.CAMERA) // Запит дозволу на використання камери
            }

            // Перевірка та запит дозволу на використання мікрофону
            val requestAudioPermission = registerForActivityResult(
                ActivityResultContracts.RequestPermission() // Контракт для запиту дозволу
            ) { granted ->
                if (!granted) {
                    Log.e("MainActivity", "Audio permission not granted")
                }
            }
            // Якщо дозвіл не надано, запитуємо його
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO) // Запит дозволу на використання мікрофону
            }

            setContent {
                PhoneWebcamTheme {
                    AppContent()
                }
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy called!")
        Toast.makeText(this, "onDestroy called", Toast.LENGTH_SHORT).show()
        connectionManager.releaseResources()
        audioManager.releaseResources()
        cameraManager.releaseResources()

    }

    // Перевірка, чи увімкнено USB-тетеринг
    private fun isUsbTetheringEnabled(context: Context): Boolean {
        Log.d(TAG, "Перевірка стану USB-тетеринга...")

        try {
            // Отримання ConnectivityManager для перевірки мереж
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // Перевірка стану мереж для Android 5.0  і вище
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val networks = connectivityManager.allNetworks //Отримання всіх доступних мереж
                for (network in networks) { // Отримання можливостей (властивостей) кожної мережі
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    Log.d(TAG, "Перевірка мережі , capabilities: $capabilities")
                    // Якщо мережа має транспорт USB, то USB-тетеринг увімкнено
                    if (capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_USB)) {
                        Log.d(TAG, "USB-тетеринг увімкнено через TRANSPORT_USB.")
                        return true
                    }
                }
            }

            // Якщо метод TRANSPORT_USB не дав результату, перевірка системних інтерфейсів
            val tetheredIfaces = getTetheredIfaces() //Список мережевих інтерфейсів
            // Перевырка, чи є серед них "rndis" (інтерфейс USB-тетеринга)
            if (tetheredIfaces.any { it.contains("rndis") }) {
                Log.d(TAG, "USB-тетеринг активен через интерфейсы (rndis).")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Помилка при перевірці стану USB-тетеринга", e)
        }

        Log.d(TAG, "USB-тетеринг не увімкненон або не вдалося визначити його стан.")
        return false
    }

    //Отримання всіх прив'язаних мережевих інтерфейсів
    private fun getTetheredIfaces(): List<String> {
        val tetheredIfaces = mutableListOf<String>() //Список для збереження інтерфейсів

        try {
            // Системна команда "ip link" для отримання мережевих інтерфейсів
            val process = Runtime.getRuntime().exec("ip link")
            process.inputStream.bufferedReader().useLines { lines ->
                //Читання всіх ліній, які повернула команда
                lines.forEach { line ->
                    Log.d(TAG, "Інтерфейс: $line")
                    // Якщо інтерфейс містить "rndis" або "usb"
                    if (line.contains("rndis") || line.contains("usb")) {
                        tetheredIfaces.add(line)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Помилка при виконанні команди ip link", e)
        }

        return tetheredIfaces
    }

    // Відкриття налаштувань USB-тетеринга
    private fun openTetherSettings(context: Context) {
        try {
            // Намір для відкриття конкретного екрану "Точка доступу і модем"
            val tetherSettingsIntent = Intent().apply {
                setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK // Відкриття активності як нового завдання
            }
            context.startActivity(tetherSettingsIntent) //Запуск налаштувань
            Toast.makeText(
                context,
                "Відкриваю налаштування \"Точка доступу і модем\".",
                Toast.LENGTH_LONG
            ).show() // Вспливаюче повідомлення для підтвердження
        } catch (e: Exception) {
            // Якщо екран "Точка доступу і модем" недоступний, ловимо помилку
            Log.e(TAG, "Помиилка при відкритті TetherSettingsActivity", e)
            try {
                // Намір для відкриття головного меню налаштувань
                val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(settingsIntent) //Запуск головних налаштувань смартфону
                Toast.makeText(
                    context,
                    "Відкриваю головне меню налаштувань. Знайдіть \\\"Точка доступу і модем\\\" вручну.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (innerException: Exception) {
                // Якщо навіть головні налаштування недоступні
                Log.e(TAG, "Помилка при відкритті головних налаштувань", innerException)
                Toast.makeText(
                    context,
                    "Не вдалося відкрити налаштування. Перевірте права доступу.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppContent() {
        // Отримуємо доступ до SharedPreferences для збереження/читання даних користувача
        val sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        var selectedScreen by remember { mutableStateOf(ConnectionType.WIFI) } // Обраний экран
        var connectionType by remember { mutableStateOf(ConnectionType.WIFI) } // Обраний тип подключения
        var showSettingsSheet by remember { mutableStateOf(false) } // Видимість екрану налаштувань
        var showAuthSheet by remember { mutableStateOf(false) } // Видимість екрану авторизації
        var isPremium by remember { mutableStateOf(false) } // Стан преміум-доступу користувача
        var username by remember { mutableStateOf("") }
        val showUsbDialog = remember { mutableStateOf(false) } // Відображення діалог USB
        val context = LocalContext.current

        //При запуску завантаження збережених даних
        LaunchedEffect(Unit) {
            val savedUsername = sharedPreferences.getString("username", null) // Збережений логін
            val savedIsPremium = sharedPreferences.getBoolean("isPremium", false) // Збережений пароль
            if (savedUsername != null) {
                username = savedUsername
                isPremium = savedIsPremium
            }
        }

        BackHandler {
            when {
                showSettingsSheet -> showSettingsSheet = false // Закрити екран налаштувань
                showAuthSheet -> showAuthSheet = false // Закрити екран авторизації
                selectedScreen == ConnectionType.LOGIN || selectedScreen == ConnectionType.SIGNUP -> {
                    selectedScreen = ConnectionType.WIFI // Повернутися на Wi-Fi екран
                }
                selectedScreen == ConnectionType.PREMIUM -> {
                    selectedScreen = ConnectionType.WIFI // Повернутися на гна Wi-Fi екран
                }
                else -> (context as? Activity)?.finish() // Закрити застосунок
            }
        }

        Scaffold(
            bottomBar = { // Нижнэ меню
                BottomNavigationBar(
                    selectedScreen = selectedScreen, // Поточний екран
                    onScreenSelected = { newScreen ->
                        if (newScreen == ConnectionType.USB) {
                            val isUsbEnabled = isUsbTetheringEnabled(context) // Перевіряємо, чи ввімкнено USB-тетеринг
                            if (isUsbEnabled) {
                                connectionType = ConnectionType.USB // Встановлення USB як тип з'єднання
                                selectedScreen = newScreen
                            } else {
                                showUsbDialog.value = true // Показати діалог, якщо USB-тетеринг вимкнений
                            }
                        } else if (newScreen == ConnectionType.WIFI) {
                            connectionType = ConnectionType.WIFI // Встановлення Wi-Fi як тип з'єднання
                            selectedScreen = newScreen
                        } else {
                            selectedScreen = newScreen // Для інших екранів просто змына selectedScreen
                        }
                    },
                    onSettingsClicked = { showSettingsSheet = true }, // Відкриття екрану налаштувань
                    onAccountClicked = { showAuthSheet = true }, //Відкриття мену вибору способу аавтентифіакції
                    isAuthorized = username.isNotEmpty() // Перевірка, чи авторизований користувач
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                // Логіка відображення залежно від обраного екрану
                when (selectedScreen) {
                    // Екрани Wi-Fi та USB
                    ConnectionType.WIFI, ConnectionType.USB -> ConnectionScreen(
                        connectionType = connectionType, // Передача типу з'єднання
                        cameraManager = cameraManager, // Управління камерою
                        connectionManager = connectionManager, //Управління з'єднанням
                        audioManager = audioManager,//Управління аудіо
                        onBack = { selectedScreen = ConnectionType.WIFI } // Дія для кнопки назад
                    )
                    // Екран авторизації
                    ConnectionType.LOGIN -> LoginScreen(
                        onBack = { /* */ },
                        onSignUp = { selectedScreen = ConnectionType.SIGNUP }, // Перехід на екран реєстрації
                        onNavigateToPremium = { isPremiumUser, userLogin ->
                            isPremium = isPremiumUser //Оновлення преміум-статусу
                            username = userLogin // Збереження імені користувача
                            saveUserToPreferences(sharedPreferences, userLogin, isPremiumUser) // Збереження даних
                            selectedScreen = ConnectionType.PREMIUM //Перехід на екран преміуму
                        }
                    )

                    //Екран реєстрації
                    ConnectionType.SIGNUP -> SignUpScreen(
                        onBack = { selectedScreen = ConnectionType.LOGIN }, // Повернення на екран авторизації
                        onLogIn = { selectedScreen = ConnectionType.LOGIN },// Перехід на екран авторизації
                        onNavigateToPremium = { isPremiumUser, userLogin ->
                            isPremium = isPremiumUser // Оновлюємо преміум-статус
                            username = userLogin.toString()// Збереження імені користувача
                            saveUserToPreferences(sharedPreferences, userLogin.toString(), isPremiumUser) // Збереження даних
                            selectedScreen = ConnectionType.PREMIUM//Перехід на екран преміуму
                        }
                    )

                    // Екран преміум
                    ConnectionType.PREMIUM -> PremiumScreen(
                        isPremium = isPremium, // Преміум-статус
                        username = username,  // Ім'я користувача
                        onLogout = { //при виході
                            isPremium = false // Відключення преміум
                            username = "" // Очищення імені користувача
                            SettingsStore.quality = "480p" //Скидання якості на стандартну
                            clearUserFromPreferences(sharedPreferences) // Очищення збережених даних
                            selectedScreen = ConnectionType.LOGIN //Перехід на екран авторизації
                        },
                        onPremiumUpdate = { newPremiumStatus ->
                            isPremium = newPremiumStatus // Оновлення преміум-статусу
                            saveUserToPreferences(sharedPreferences, username, newPremiumStatus) // Оновлення даних в SharedPreferences
                        },
                        sharedPreferences = sharedPreferences // Передача SharedPreferences в PremiumScreen
                    )
                }

                // Вспливаючий екран налаштувань
                if (showSettingsSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showSettingsSheet = false }
                    ) {
                        SettingsScreen(isPremium = isPremium)
                    }
                }

                // Меню вибору автентифікації
                if (showAuthSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showAuthSheet = false }
                    ) {
                        AuthScreen(
                            onLogin = {
                                showAuthSheet = false
                                selectedScreen = ConnectionType.LOGIN // Перехід на екран логіну
                            },
                            onSignUp = {
                                showAuthSheet = false
                                selectedScreen = ConnectionType.SIGNUP // Перехід на екран реєстрації
                            }
                        )
                    }
                }

                // Діалог USB
                if (showUsbDialog.value) {
                    UsbTetheringDialog(
                        onOk = { showUsbDialog.value = false },
                        onSettings = { openTetherSettings(context) }
                    )
                }
            }
        }
    }


//Збереження даних користувача
    private fun saveUserToPreferences(sharedPreferences: SharedPreferences, username: String, isPremium: Boolean) {
        val editor = sharedPreferences.edit()// Об'єкт Editor для внесення змін у SharedPreferences
        editor.putString("username", username) //Збереження імені користувача
        editor.putBoolean("isPremium", isPremium) // Збереження статусу користувача
        editor.apply() //Застосування змін
    }


//Очистка даних користувача
    private fun clearUserFromPreferences(sharedPreferences: SharedPreferences) {
        val editor = sharedPreferences.edit()// Об'єкт Editor для внесення змін у SharedPreferences
        editor.clear()//Очистка всіх даних у SharedPreferences
        editor.apply()//Застосування змін
    }

    //Ниєнє меню
    @Composable
    fun BottomNavigationBar(
        selectedScreen: ConnectionType, //Вибраний екран
        onScreenSelected: (ConnectionType) -> Unit, // Callback для зміни екрану
        onSettingsClicked: () -> Unit = {}, // Callback для натискання на кнопку налаштувань
        onAccountClicked: () -> Unit = {}, // Callback для натискання на кнопку аккаунта
        isAuthorized: Boolean = false // Статус авторизації користувача
    ) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.height(56.dp)
        ) {
            // Wi-Fi
            NavigationBarItem(
                selected = false, //Без підсвітки
                onClick = { onScreenSelected(ConnectionType.WIFI) },
                icon = {
                    Icon(
                        painter = painterResource( //Зміна іконки в залежності від вибраного ерана
                            id = if (selectedScreen == ConnectionType.WIFI) R.drawable.wifi_on else R.drawable.wifi_off
                        ),
                        contentDescription = "Wi-Fi",
                        modifier = Modifier.size(30.dp),
                        tint = Color.Unspecified
                    )
                }
            )

            // USB
            NavigationBarItem(
                selected = false, //Без підсвітки
                onClick = { onScreenSelected(ConnectionType.USB) },
                icon = {
                    Icon(
                        painter = painterResource( //Зміна іконки в залежності від вибраного ерана
                            id = if (selectedScreen == ConnectionType.USB) R.drawable.usb_on else R.drawable.usb_off
                        ),
                        contentDescription = "USB",
                        modifier = Modifier.size(30.dp),
                        tint = Color.Unspecified
                    )
                }
            )

            // Settings
            NavigationBarItem(
                selected = false, //Без підсвітки
                onClick = onSettingsClicked,
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.settings_off),
                        contentDescription = "Settings",
                        modifier = Modifier.size(30.dp),
                        tint = Color.Unspecified
                    )
                }
            )

            // Account
            NavigationBarItem(
                selected = false,
                onClick = {
                    if (isAuthorized) {
                        // Якщо користувач авторизований,  екран Premium
                        onScreenSelected(ConnectionType.PREMIUM)
                    } else {
                        // Якщо користувач не авторизований, екран авторизації
                        onAccountClicked()

                    }
                },
                icon = {
                    Icon( // Іконка аккаунта (змінюється залежно від обраного екрану)
                        painter = painterResource(
                            id = if (selectedScreen == ConnectionType.PREMIUM ||
                                selectedScreen == ConnectionType.LOGIN ||
                                selectedScreen == ConnectionType.SIGNUP) R.drawable.user_on else R.drawable.user_off
                        ),
                        contentDescription = "Account",
                        modifier = Modifier.size(30.dp),
                        tint = Color.Unspecified
                    )
                }
            )
        }
    }


    //Меню вибору способу автентифікації
    @Composable
    fun AuthScreen(onLogin: () -> Unit, onSignUp: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Кнопка "Log in"
            Button(
                onClick = onLogin,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xffed95ac)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Text(
                    text = "Log In",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = NunitoFontFamily,
                    color = Color.White
                )
            }

            // Кнопка "Sign Up"
            Button(
                onClick = onSignUp,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xffed95ac)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Text(
                    text = "Sign Up",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = NunitoFontFamily,
                    color = Color.White
                )
            }
        }
    }

    //Діалогове вікно з проханням увімкнути  Usb Tethering
    @Composable
    fun UsbTetheringDialog(onOk: () -> Unit, onSettings: () -> Unit) {
        AlertDialog(
            onDismissRequest = { onOk() },
            title = { Text("To use USB Streaming") },
            text = { Text("Turn on USB modem mode (USB tethering) to proceed.") },
            confirmButton = {
                TextButton(onClick = { onSettings() }) {
                    Text("Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { onOk() }) {
                    Text("OK")
                }
            }
        )
    }


    //Екран налаштувань
    @Composable
    fun SettingsScreen(isPremium: Boolean) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Група налаштувань для вибору камери
            SettingToggleGroup(
                title = "Camera",
                options = listOf("Front", "Back"), // Варіанти вибору
                initialSelectedIndex = if (SettingsStore.cameraType == "Front") 0 else 1 // Вибір початкового значення
            ) { selected ->
                SettingsStore.cameraType = selected // Збереження вибраного значення в SettingsStore
            }

            // Група налаштувань для увімкнення звуку
            SettingToggleGroup(
                title = "Sound",
                options = listOf("On", "Off"),  // Варіанти вибору
                initialSelectedIndex = if (SettingsStore.sound == "On") 0 else 1 // Вибір початкового значення
            ) { selected ->
                SettingsStore.sound = selected // Збереження вибраного значення в SettingsStore
            }

            SettingToggleGroup(
                title = "Quality",
                options = listOf("480p", "960p", "MAX"), // Варіанти вибору
                initialSelectedIndex = when (SettingsStore.quality) { // Вибір початкового значення
                    "480p" -> 0
                    "960p" -> 1
                    "MAX" -> 2
                    else -> 0
                },
                hasCrown = true, //Наявна іконка корони
                isPremium = isPremium // Передача наявності преміум статуса
            ) { selected ->
                SettingsStore.quality = selected // Збереження вибраного значення в SettingsStore
            }
        }
    }


    object SettingsStore { // Об'єкт для зберігання налаштувань
        var cameraType: String by mutableStateOf("Front") // Вибраний тип камери
        var sound: String by mutableStateOf("On") // Обраний звук
        var quality: String by mutableStateOf("480p") //Якість відео
    }

    @Composable
    fun SettingToggleGroup(
        title: String, //Назва групи налаштувань
        options: List<String>, //Список доступних варіантів
        initialSelectedIndex: Int,  // Початково вибраний індекс
        hasCrown: Boolean = false, //Чи показувати корону
        isPremium: Boolean = true,  // Чи є користувач преміум-користувачем
        onSelectedChange: (String) -> Unit
    ) {
        // Вибраний індекс зберігається у стані
        var selectedIndex by remember { mutableStateOf(initialSelectedIndex) }

        Column(
            modifier = Modifier
                .fillMaxWidth()

                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Заголовок групи
            Text(
                text = title,
                fontSize = 24.sp,
                fontFamily = NunitoFontFamily,
                fontWeight = FontWeight.Bold,
                color = Color(0xffed95ac),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(
                        color = Color(0xffed95ac),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Елементи меню
                options.forEachIndexed { index, option ->
                    // Перевірка, чи опція доступна (непреміум-користувач не може вибрати преміум-опції)
                    val isDisabled = !isPremium && (index > 0)
                    // Кожен пункт меню
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                // білий напівпрозорий фон для обраної опції
                                color = if (selectedIndex == index) Color.White.copy(alpha = 0.70f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp) // Скругление белого прямоугольника
                            )
                            .clickable(
                                enabled = !isDisabled, // Якщо опція недоступна, кліки вимикаються
                                onClick = {
                                    selectedIndex = index
                                    onSelectedChange(option)
                                }
                            )
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Текст і корона
                        Box(contentAlignment = Alignment.TopCenter) {
                            // Корона для 960p та MAX
                            if (hasCrown && index > 0) {
                                Image(
                                    painter = painterResource(id = R.drawable.crown),
                                    contentDescription = "Crown",
                                    modifier = Modifier
                                        .size(29.dp) // Размер короны
                                        .align(Alignment.TopCenter) // Позиционирование короны сверху
                                        .offset(y = (-48).dp) // Смещение вверх, чтобы она была над текстом
                                )
                            }

                            // Текст пункту меню
                            Text(
                                text = option,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                // Колір тексту вибраного пункту змінюється
                                color = if (selectedIndex == index) Color(0xffed95ac) else Color.White
                            )
                        }
                    }
                }
            }
        }
    }


    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
    @Composable
    fun ConnectionScreen(
        connectionType: ConnectionType, //Wi-Fi або USB
        cameraManager: CameraManager, // Управління камерою
        connectionManager: ConnectionManager, // Управління мережею
        audioManager: AudioManager,// Управління звуком
        onBack: () -> Unit
    ) {
        BackHandler {
            onBack()
        }

        val context = LocalContext.current // Локальний контекст
        val lifecycleOwner = LocalLifecycleOwner.current // Життєвий цикл компонента
        val cameraType = SettingsStore.cameraType // Поточний тип камери
        val quality = SettingsStore.quality// Поточна якість відео
        val sound = SettingsStore.sound// Поточний стан звуку

        val coroutineScope = rememberCoroutineScope() // Coroutine Scope для асинхронних задач
        var isRefreshing by remember { mutableStateOf(false) } // Стан оновлення (Pull-to-Refresh)
        val ipAddress = remember { mutableStateOf("") }// IP-адреса сервера
        val isStreamingState = remember { mutableStateOf(connectionManager.isStreaming) }  // Стан трансляції
        val keyboardController = LocalSoftwareKeyboardController.current // Контролер клавіатури

        // Функція для отримання IP-адреси сервера
        fun fetchServerIp(
            connectionType: ConnectionType,//Wi-Fi або USB
            connectionManager: ConnectionManager,
            ipAddress: MutableState<String>
        ) {
            val triggerWord = "LocateHOST" // Триггерне слово для запиту
            val serverIp = if (connectionType == ConnectionType.WIFI) {
                "192.168.0.255" // Ширококанальна адреса для Wi-Fi
            } else {
                "192.168.42.255" // Ширококанальна адреса для USB
            }
            val port = 8091 // Порт для зв'язку

            // Надсилання запиту і очікування відповіді
            connectionManager.sendTriggerAndWaitForResponse(triggerWord, serverIp, port) { responseIp ->
                if (responseIp != null) {
                    ipAddress.value = responseIp // Збереження  отриманої IP
                } else {
                    Log.e("ConnectionScreen", "Failed to fetch IP address for $connectionType")
                }
            }
        }


        // Запуск запиту IP при завантаженні
        LaunchedEffect(connectionType) {

            cameraManager.setStreamingState(false) // Вимикненя трансляції камери
            isStreamingState.value = false // Скидаємо стан трансляції
            connectionManager.close() // Закриття з'єднання
            fetchServerIp(connectionType, connectionManager, ipAddress) // Отримання IP-адреси
        }


        // Стан Pull-to-Refresh
        val pullRefreshState = rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true// Стан "Оновлення"
                coroutineScope.launch {
                    fetchServerIp(connectionType, connectionManager, ipAddress) // Оновлення IP-адресм
                    isRefreshing = false// Скидання стану "Оновлення"
                }
            }
        )

        // Для отримання дозволу на запис аудіо
        val requestAudioPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Log.d("ConnectionScreen", "Microphone permission granted")
                SettingsStore.sound = "On" //
                audioManager.startAudioCapture( // Початок запису аудіо
                    onAudioFrame = { audioFrame ->
                        connectionManager.sendAudioFrame(audioFrame) // Відправка аудіо-кадрів
                    }
                )
            } else {
                Log.e("ConnectionScreen", "Microphone permission not granted, disabling sound")
                SettingsStore.sound = "Off"
            }
        }

        // Для запуску камепи
        LaunchedEffect(cameraType, quality) {
            cameraManager.startCamera(context, lifecycleOwner, cameraType, quality) { frameData ->
                connectionManager.sendEncodedFrame(frameData) // Передача кадров через ConnectionManager
            }
        }

        LaunchedEffect(sound) {
            if (sound == "On") {
                audioManager.startAudioCapture(
                    onAudioFrame = { audioFrame -> // Відправка аудіофреймів
                        connectionManager.sendAudioFrame(audioFrame)
                    },
                    requestPermission = {
                        // Запит дозволу, якщо відсутній
                        requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            } else {
                audioManager.stopAudioCapture() // Стоп запис звуку
            }
        }

        //
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pullRefresh(pullRefreshState) //  Pull-to-Refresh
                        .verticalScroll(rememberScrollState())// Скролл для контенту
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    keyboardController?.hide() // Сховати клавіатуру при натисканні
                                }
                            )
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize(),

                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Заголовок Wi-Fi або USB
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, start = 16.dp),
                            contentAlignment = Alignment.TopStart
                        ) {
                            Text(
                                text = when (connectionType) {
                                    ConnectionType.WIFI -> "Wi-Fi"
                                    ConnectionType.USB -> "USB"
                                    else -> ""
                                },
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xffed95ac)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Поле для IP-адреси
                        OutlinedTextField(
                            value =  ipAddress.value,
                            onValueChange = {  ipAddress.value = it }, // Можливість змінювати IP вручну
                            label = { Text("IP Address") },
                            placeholder = { Text("") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                focusedBorderColor = Color(0xffed95ac),
                                unfocusedBorderColor = Color.Gray,
                                cursorColor = Color(0xffed95ac)
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                }
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Кнопка "Start Stream" / "Stop Stream"
                        Button(
                            onClick = {
                                val newStreamingState = !isStreamingState.value
                                connectionManager.setStreamingState(newStreamingState)
                                cameraManager.setStreamingState(newStreamingState) // Новий стан в CameraManager
                                audioManager.setStreamingState(newStreamingState)

                                if (newStreamingState) {
                                    connectionManager.connect( ipAddress.value, 8090) // Підключення до вказаногї IP-адреси
                                    Log.d("Stream", "connecting to...: $ipAddress.value")

                                } else {
                                    connectionManager.close() // Закриття з'єднання
                                    cameraManager.startCamera( //Перезапуск камери
                                        context,
                                        lifecycleOwner,
                                        cameraType,
                                        quality
                                    ) { frameData ->
                                        connectionManager.sendEncodedFrame(frameData)
                                    }
                                    if (sound == "On") {
                                        audioManager.startAudioCapture(
                                            onAudioFrame = { audioFrame ->
                                                connectionManager.sendAudioFrame(audioFrame)
                                            },
                                            requestPermission = {
                                                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        )
                                    }
                                }
                                isStreamingState.value = newStreamingState
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xffed95ac)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(60.dp)
                        ) {
                            Text(
                                text = if (isStreamingState.value) "Stop Stream" else "Start Stream",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = NunitoFontFamily,
                                color = Color.White,
                            )
                        }


                        AndroidView(
                            factory = {
                                PreviewView(it).apply {
                                    this.scaleType = PreviewView.ScaleType.FIT_CENTER
                                    cameraManager.bindPreview(this)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .aspectRatio(3f / 4f) // Прев'ю відео 3:4
                        )
                    }

                    // Індикатор Pull-to-Refresh
                    PullRefreshIndicator(
                        refreshing = isRefreshing,
                        state = pullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun LoginScreen(
        onBack: () -> Unit,
        onSignUp: () -> Unit, // Функція для переходу на екран реєстрації
        onNavigateToPremium: (Boolean, String) -> Unit  // Функція для переходу на екран преміуму
    ) {
        val scope = rememberCoroutineScope()
        val apiClient = remember { GlobalServer() }
        var login by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf("") }
        val keyboardController = LocalSoftwareKeyboardController.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        keyboardController?.hide() // При натисканні на пусте місце приховати клавіатуру
                    })
                }
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                // Заголовок
                Text(
                    text = "Log in to your account",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xffed95ac),
                    fontFamily = NunitoFontFamily,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Підзаголовок з можливістю переходу на екран реєстрації
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF767676), fontWeight = FontWeight.Normal)) {
                            append("Don't have an account? ")
                        }
                        pushStringAnnotation(tag = "SIGN_UP", annotation = "SignUp")
                        withStyle(SpanStyle(color = Color(0xFFED95AC), fontWeight = FontWeight.Bold)) {
                            append("Sign Up")
                        }
                        pop()
                    },
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = NunitoFontFamily,
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .clickable(onClick = { onSignUp() })
                )

                // Поле для вводу логіну
                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    label = { Text("Login") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Color(0xffed95ac),
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color(0xffed95ac)
                    )
                )

                // Поле для вводу пароля
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Color(0xffed95ac),
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color(0xffed95ac)
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    )
                )

                // Кнопка "Log In"
                Button(
                    onClick = {
                        // Перевірка, чи всі поля заповнені
                        if (login.isEmpty() || password.isEmpty()) {
                            errorMessage = "Login and Password cannot be empty"
                            return@Button
                        }

                        // Пошук сервера перед відправкою запиту
                        scope.launch {
                            val isServerFound = apiClient.findAndSetServerUrl(
                                triggerWord = "LocateHOST",
                                serverIp = "192.168.0.255",
                                port = 8092
                            )
                            if (isServerFound) {
                                // Якщо сервер знайдено, виконуємо запит на авторизацію
                                try {
                                    val response = apiClient.login(login, password)
                                    if (response.message == "Login successful") {
                                        onNavigateToPremium(response.isPremium, login)
                                    } else {
                                        errorMessage = response.message.toString()
                                    }
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "Unknown error"
                                }
                            } else {
                                errorMessage = "Unable to find server. Please try again."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xffed95ac)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(60.dp)
                ) {
                    Text(
                        text = "Log In",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = NunitoFontFamily,
                        color = Color.White
                    )
                }

                // Повідомлення про помилку, якщо є
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = Color.Red,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }



    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SignUpScreen(
        onBack: () -> Unit,
        onLogIn: () -> Unit,
        onNavigateToPremium: (Boolean, Any?) -> Unit
    ) {
        val scope = rememberCoroutineScope()
        val apiClient = remember { GlobalServer() }
        var email by remember { mutableStateOf("") }
        var login by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf("") }
        val keyboardController = LocalSoftwareKeyboardController.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        keyboardController?.hide() // Сховати клавіатуру
                    })
                }
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                // Заголовок
                Text(
                    text = "Create your account",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xffed95ac),
                    fontFamily = NunitoFontFamily
                )

                // Поля вводу
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Color(0xffed95ac),
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color(0xffed95ac)
                    )
                )
                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    label = { Text("Login") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Color(0xffed95ac),
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color(0xffed95ac)
                    )
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Color(0xffed95ac),
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color(0xffed95ac)
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    )
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm Password") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Color(0xffed95ac),
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color(0xffed95ac)
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    )
                )

                // Кнопка "Sign Up"
                Button(
                    onClick = {
                        val error = apiClient.validateInput(email, login, password, confirmPassword)
                        if (error != null) {
                            errorMessage = error
                            return@Button
                        }

                        // Пошук сервера перед реєстрацією
                        scope.launch {
                            val isServerFound = apiClient.findAndSetServerUrl(
                                triggerWord = "LocateHOST",
                                serverIp = "192.168.0.255",
                                port = 8092
                            )
                            if (isServerFound) {
                                try {
                                    val response = apiClient.register(email, login, password)
                                    onNavigateToPremium(response.contains("Premium"), login)
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "Unknown error"
                                }
                            } else {
                                errorMessage = "Unable to find server. Please try again."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xffed95ac)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(60.dp)
                ) {
                    Text(
                        text = "Sign Up",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = NunitoFontFamily,
                        color = Color.White
                    )
                }

                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = Color.Red,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }


    //Екран  преміуму
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @Composable
    fun PremiumScreen(
        isPremium: Boolean,
        username: String,
        onLogout: () -> Unit,
        onPremiumUpdate: (Boolean) -> Unit, // Callback для оновлення статусу
        sharedPreferences: SharedPreferences // SharedPreferences для збереження
    ) {
        val scope = rememberCoroutineScope()
        val apiClient = remember { GlobalServer() }
        var errorMessage by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Верхня панель
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end=8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Account",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xffed95ac),
                    fontFamily = NunitoFontFamily,
                )
                IconButton(onClick = { onLogout() }) {
                    Icon(
                        painter = painterResource(id = R.drawable.logout),
                        contentDescription = "Logout",
                        tint = Color.Unspecified
                    )
                }
            }

            // Привітання
            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = Color(0xFF767676), fontWeight = FontWeight.Bold)) {
                        append("Hi, ")
                    }
                    withStyle(style = SpanStyle(color = Color(0xFFED95AC), fontWeight = FontWeight.Bold)) {
                        append(username)
                    }
                    withStyle(style = SpanStyle(color = Color(0xFF767676), fontWeight = FontWeight.Bold)) {
                        append("!")
                    }
                },
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .padding(horizontal = 16.dp)
            )

            // Повідомлення про статус
            Text(
                text = buildAnnotatedString {
                    if (isPremium) {
                        withStyle(style = SpanStyle(color = Color(0xFF767676), fontWeight = FontWeight.Bold)) {
                            append("You are ")
                        }
                        withStyle(style = SpanStyle(color = Color(0xFFED95AC), fontWeight = FontWeight.Bold)) {
                            append("Premium")
                        }
                        withStyle(style = SpanStyle(color = Color(0xFF767676), fontWeight = FontWeight.Bold)) {
                            append(" user!")
                        }
                    } else {
                        withStyle(style = SpanStyle(color = Color(0xFF767676), fontWeight = FontWeight.Bold)) {
                            append("You don’t have ")
                        }
                        withStyle(style = SpanStyle(color = Color(0xFFED95AC), fontWeight = FontWeight.Bold)) {
                            append("Premium")
                        }
                        withStyle(style = SpanStyle(color = Color(0xFF767676), fontWeight = FontWeight.Bold)) {
                            append(" yet.")
                        }
                    }
                },
                fontSize = 28.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Блок з зображенням
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                if (isPremium) {
                    Icon(
                        painter = painterResource(id = R.drawable.big_crown),
                        contentDescription = "Crown Icon",
                        modifier = Modifier
                            .size(65.dp)
                            .align(Alignment.TopCenter),
                        tint = Color(0xFFED95AC)
                    )
                }
                Image(
                    painter = painterResource(id = R.drawable.premium_image),
                    contentDescription = null,
                    modifier = Modifier
                        .size(if (isPremium) 360.dp else 260.dp)
                        .padding(top = if (isPremium) 70.dp else 0.dp)
                        .offset(x = 15.dp)
                        .align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Прямокутник з ціною і придбанням / подякою
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.91f)
                    .wrapContentHeight()
                    .offset(x=16.dp)
                    .shadow(
                        elevation = 15.dp,
                        shape = RoundedCornerShape(8.dp),
                        clip = false
                    )
                    .background(
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(vertical = 16.dp)

            ) {
                if (isPremium) { // Текст, який бачать преміум користувачі
                    Text(
                        text = "Thank you\nfor supporting!",
                        fontSize = 36.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color(0xFFED95AC),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {//Вміст боксу для користувачів без преміуму
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = Color(0xFFED95AC).copy(alpha = 0.2f)
                                )
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = "5$",
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = NunitoFontFamily,
                                color = Color(0xFFED95AC),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }

                        Text(
                            text = "✓ stream resolution up to 2K",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFFED95AC),
                            modifier = Modifier.padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                        )

                        // Кнопка "Buy Now!"
                        Button(
                            onClick = {
                                // Пошук сервера перед відправкою запиту
                                scope.launch {
                                    val isServerFound = apiClient.findAndSetServerUrl(
                                        triggerWord = "LocateHOST",
                                        serverIp = "192.168.0.255",
                                        port = 8092
                                    )
                                    if (isServerFound) {
                                        // Якщо сервер знайдено, виконуємо запит на покупку Premium
                                        try {
                                            val response = apiClient.buyPremium(username) // Запит до сервера
                                            if (response.isPremium) { // Якщо вдала покупка
                                                // Оновлення статусу в SharedPreferences
                                                val editor = sharedPreferences.edit()
                                                editor.putBoolean("isPremium", true)
                                                editor.apply()

                                                // Виклик callback для оновлення екрана
                                                onPremiumUpdate(true)
                                            } else {
                                                errorMessage = "Failed to upgrade to Premium"
                                            }
                                        } catch (e: Exception) {
                                            errorMessage = e.message ?: "Unknown error"
                                        }
                                    } else {
                                        errorMessage = "Unable to find server. Please try again."
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFED95AC)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(60.dp)
                        ) {
                            Text(
                                text = "Buy Now!",
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = NunitoFontFamily,
                                color = Color.White,
                                modifier = Modifier.offset(y = -2.dp)
                            )
                        }
                    }
                }
            }
            // Повідомлення про помилку, якщо є
            if (errorMessage.isNotEmpty()) {
                Text(
                    text = errorMessage,
                    color = Color.Red,
                    modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
                )
            }
        }
    }

    enum class ConnectionType { // Можливі екрани
        WIFI, USB, LOGIN, SIGNUP, PREMIUM
    }
    @Serializable
    data class RegisterRequest(val email: String, val login: String, val password: String)

    @Serializable
    data class LoginRequest(val login: String, val password: String)

    @Serializable
    data class LoginResponse(val message: String? = null, val isPremium: Boolean, val login: String? = null)
}


class GlobalServer {

    // Базовий URL для запитів до сервера
    private var baseUrl : String? = null

    // Ініціалізація клієнта HTTP для виконання запитів
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })  // Підключення обробки JSON-формату, ігнорування невідомих ключів
        }
    }

    // Валідація вхідних даних (email, login, password)
    fun validateInput(email: String?, login: String?, password: String?, confirmPassword: String?): String? {
        if (email != null && !isEmailValid(email)) return "Email must be valid and less than 30 characters"
        if (login != null && !isLoginValid(login)) return "Login must be 4-12 characters and can only include letters, numbers, underscores (_) and dots (.)"
        if (password != null && !isPasswordValid(password)) return "Password must be 8-15 characters, include one uppercase, one lowercase, one number, and one special character (_$.!)"
        if (password != null && confirmPassword != null && password != confirmPassword) return "Passwords do not match"
        return null // Якщо всі перевірки пройдено, повернення null (немає помилок)
    }

    // Хешування пароля з використанням SHA-256
    fun hashPassword(password: String): String {
        val salt = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val digest = MessageDigest.getInstance("SHA-256") // Алгоритм SHA-256
        val saltedPassword = password + salt
        val hashBytes = digest.digest(saltedPassword.toByteArray()) // Генерація хеш пароля
        return hashBytes.joinToString("") { "%02x".format(it) } // Перетворення байтів в шістнадцятковий рядок
    }

    // Реєстрація
    suspend fun register(email: String, login: String, password: String): String {
        val hashedPassword = hashPassword(password)
        val request = MainActivity.RegisterRequest(email, login, hashedPassword)

        return try {
            // Відправка POST-запиту  "/register" на сервер
            val response: HttpResponse = client.post("$baseUrl/register") {
                contentType(ContentType.Application.Json) // Відправка JSON
                setBody(request) // Об'єкт з email, login і hashedPassword
            }
            response.bodyAsText() // Текстова відповідь від сервера
        } catch (e: Exception) {
            throw Exception("Registration failed: ${e.message}")
        }
    }

    // Авторизація
    suspend fun login(login: String, password: String): MainActivity.LoginResponse {
        val hashedPassword = hashPassword(password) // Хешування паролю перед відправкою на сервер
        val request = MainActivity.LoginRequest(login, hashedPassword)

        return try {
            val response: HttpResponse = client.post("$baseUrl/login") {
                contentType(ContentType.Application.Json) // Відправка JSON
                setBody(request) // Об'єкт з  login і hashedPassword
            }
            response.body()  //Парсинг відповіді у форматі LoginResponse
        } catch (e: Exception) {
            throw Exception("Login failed: ${e.message}")
        }
    }

    // Перевірка  пароля
    fun isPasswordValid(password: String): Boolean {
        val passwordPattern = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[_\\$\\.\\!])[A-Za-z\\d_\\$\\.\\!]{8,15}$"
        return Pattern.matches(passwordPattern, password)
    }

    // Перевірка  логіну
    fun isLoginValid(login: String): Boolean {
        val loginPattern = "^[a-zA-Z\\d_\\.]{4,12}$"
        return Pattern.matches(loginPattern, login)
    }

    // Перевірка email
    fun isEmailValid(email: String): Boolean {
        val emailPattern = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        return email.length <= 30 && Pattern.matches(emailPattern, email)
    }

    // Придбання Преміуму
    suspend fun buyPremium(login: String): MainActivity.LoginResponse {
        return try {  // Відправка POST-запиту на сервер
            val response: HttpResponse = client.post("$baseUrl/buy_premium") {
                contentType(ContentType.Application.Json) // Відправка  JSON
                setBody(mapOf("login" to login)) // передача логіну
            }
            response.body()//Парсинг відповіді у форматі LoginResponse
        } catch (e: Exception) {
            throw Exception("Buy Premium failed: ${e.message}")
        }
    }

    suspend fun findAndSetServerUrl(triggerWord: String, serverIp: String, port: Int): Boolean {
        val foundIp = findServer(triggerWord, serverIp, port)
        return if (foundIp != null) {
            baseUrl = "http://$foundIp:7771" // Оновлюємо baseUrl знайденим IP
            Log.d("GlobalServer", "Сервер знайдено. Base URL оновлено: $baseUrl")
            true
        } else {
            Log.e("GlobalServer", "Сервер не знайдено")
            false
        }
    }
    private suspend fun findServer(triggerWord: String, serverIp: String, port: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket() // Створення тимчасового сокету
                val sendData = triggerWord.toByteArray() // Перетворення тригерного слова на байти
                val address = InetAddress.getByName(serverIp) // Визначення адреси сервера
                val packet = DatagramPacket(sendData, sendData.size, address, port) // Створення UDP-пакету
                socket.send(packet) // Відправка пакету
                Log.d("GlobalServer", "Пакет відправлено до $serverIp:$port")

                val buffer = ByteArray(1024) // Буфер для отримання відповіді
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.soTimeout = 5000 // Встановлення таймауту на 5 секунд

                try {
                    socket.receive(responsePacket) // Очікування відповіді
                    val responseMessage = String(responsePacket.data, 0, responsePacket.length) // Розшифрування відповіді
                    Log.d("GlobalServer", "Отримано відповідь: $responseMessage від ${responsePacket.address.hostAddress}")
                    responsePacket.address.hostAddress // Повертаємо IP-адресу
                } catch (e: Exception) {
                    Log.e("GlobalServer", "Відповідь не отримано: ${e.message}")
                    null // Повертаємо null, якщо відповіді немає
                } finally {
                    socket.close() // Закриття сокету
                }
            } catch (e: Exception) {
                Log.e("GlobalServer", "Помилка мережі: ${e.message}")
                null // У разі помилки повертаємо null
            }
        }
    }
}


class AudioManager(private val context: Context) {
    private var audioRecord: AudioRecord? = null // Об'єкт для запису аудіо
    private var audioExecutor: ExecutorService = Executors.newSingleThreadExecutor() // Виконавець для роботи в окремому потоці
    private var isRecording: Boolean = false // Прапорець для перевірки, чи запис активний


    private val bufferSize: Int = AudioRecord.getMinBufferSize(
        44100, // Частота дискретизації (44100 Гц — стандарт для звуку)
        AudioFormat.CHANNEL_IN_MONO, // Моно-канал
        AudioFormat.ENCODING_PCM_16BIT // Формат PCM з 16-бітною глибиною
    )

    fun startAudioCapture(
        onAudioFrame: (ByteArray) -> Unit,
        requestPermission: (() -> Unit)? = null  // Для запиту дозволу
    ) {
        // Перевірка наявності дозволу
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioManager", "Microphone permission not granted")
            MainActivity.SettingsStore.sound = "Off" // Вимкнення звуку у налаштуваннях

            // Якщо дозволу немає -  запит дозволу
            requestPermission?.invoke()
            return
        }

        // Ініціалізація AudioRecord
        if (audioRecord == null) {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,// Джерело звуку - мікрофон
                44100,// Частота дискретизації
                AudioFormat.CHANNEL_IN_MONO, // Моно-канал
                AudioFormat.ENCODING_PCM_16BIT, // Формат PCM з 16-бітною глибиною
                bufferSize // Розмір буфера
            )
        }

        // Перевірка стану AudioRecord
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioManager", "AudioRecord initialization failed")
            return
        }

        isRecording = true
        audioRecord?.startRecording() // Початок запису

        // Захоплення звуку в окремому потоці
        audioExecutor.execute {
            val audioBuffer = ByteArray(bufferSize)  // Буфер для зберігання аудіофреймів

            while (isRecording) {// Поки запис активний
                val readBytes = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0 // Читання даних з мікрофона
                if (readBytes > 0) {
                    val capturedAudio = audioBuffer.copyOf(readBytes)// Копія записаних даних
                    onAudioFrame(capturedAudio) // Передача аудіофрейму через callback
                }
            }
        }
        Log.d("AudioManager", "Audio capture started")
    }


    fun stopAudioCapture() {
        if (isRecording) { // Якщо запис активний
            isRecording = false // Зупинка запису
            audioRecord?.stop() // Зупинка AudioRecord
            audioRecord?.release() // Звільнення ресурсів
            audioRecord = null
        }

        Log.d("AudioManager", "Audio capture stopped and resources released")
    }
    fun releaseResources() {
        // Зупинка запису
        stopAudioCapture()
        audioExecutor.shutdownNow()
        Log.d("Stream destroy", "CoroutineScope cancelled and resources released")
    }

    fun setStreamingState(isRecording: Boolean) {
        this.isRecording = isRecording
        Log.d("Stream", "Streaming state set to: $isRecording")
    }

}


class CameraManager {
    //// Ініціалізація потоків для обробки кадрів і кодування
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var encodingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Черга для збереження кадрів перед кодуванням
    private val frameQueue: BlockingQueue<ByteArray> = ArrayBlockingQueue(100)

    // Компоненти для роботи з камерою
    private var preview: Preview? = null // Компонент для відображення прев'ю
    private var cameraProvider: ProcessCameraProvider? = null // Менеджер камер
    private var previewView: PreviewView? = null // Віджет для показу прев'ю
    private var mediaCodec: MediaCodec? = null // Кодувальник для відеопотоку
    private var targetResolution: Size? = null // Цільова роздільна здатність
    private var isStreaming = false // Прапорець для перевірки, чи запущена трансляція

    // Запуску камери
    fun startCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        cameraType: String,
        quality: String,
        onEncodedFrame: (ByteArray) -> Unit
    ) {
        // Встановлення цільової якості
        if (quality=="480p"){
            targetResolution = Size(480, 640)
        }
        else if (quality=="960p"){
            targetResolution = Size(960, 1280)
        }
        else if (quality=="MAX"){
            targetResolution = Size(1536, 2048)
        }
        else {
            targetResolution = Size(480, 640)
        }

        // Отримання екземпляра ProcessCameraProvider для роботи з камерою
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            // Ініціалізація CameraProvider після отримання доступу до камери
            cameraProvider = cameraProviderFuture.get()
            // Створення прев'ю для відображення відео
            preview = Preview.Builder()
//                .setTargetResolution(targetResolution!!)
                .build()

            // Створення аналізатора кадрів
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(targetResolution!!)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {  // Виклик processFrame для аналізу кадрів
                    it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                        processFrame(imageProxy,cameraType)
                    })
                }

            // Вибір камери (передня або задня)
            val cameraSelector = if (cameraType == "Front") {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }


            try {
                // Відключення всіх попередніх компонентів камери
                cameraProvider?.unbindAll()
                // Прив'язка прев'ю та аналізатора до життєвого циклу
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
                // Встановлення провайдера для прев'ю
                preview?.setSurfaceProvider(previewView?.surfaceProvider)
                Log.d("Stream", "Camera started successfully with resolution ${targetResolution!!.width}x${targetResolution!!.height}.")

                // Ініціалізація  MediaCodec
                mediaCodec = createMediaCodec()

                // Запуск потоку для кодування відео
                startEncoding(onEncodedFrame)

            } catch (exc: Exception) {
                Log.e("Stream", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context)) // Виконання у головному потоці
    }


    //Обробка кадру
    private fun processFrame(imageProxy: ImageProxy, cameraType: String) {
        // Виконання обробки у фоновому потоці
        cameraExecutor.execute {
            try {
                if (!isStreaming) {
                    // Якщо трансляція не активна, кадр пропускається
                    imageProxy.close()
                    return@execute
                }
                // Конвертація кадру в NV12 (формат для кодування)
                val nv12Data = imageProxyToNV12(imageProxy, cameraType)
                if (nv12Data == null) {
                    Log.e("Stream", "Failed to convert ImageProxy to NV12. Skipping frame.")
                    return@execute
                }

                // Якщо черга перевищує 40 кадрів, очистка
                if (frameQueue.size > 40) {
                    frameQueue.clear()
                    Log.w("Stream", "Frame queue size exceeded limit. Clearing old frames.")
                }

                // Додавання кадру в чергу
                val offered = frameQueue.offer(nv12Data)
                if (!offered) {
                    Log.d("Stream", "Frame queue is full. Dropping frame.")
                } else {
                    Log.d("Stream", "Frame added to queue. Queue size: ${frameQueue.size}")
                }

            } catch (e: Exception) {
                Log.e("Stream", "Error processing frame", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    // Запуск потоку кодування кадрів
    private fun startEncoding(onEncodedFrame: (ByteArray) -> Unit) {
        // Запуск кодування у фоновому потоці encodingExecutor
        encodingExecutor.execute {
            while (true) {
                try {
                    if (!isStreaming) {// Якщо трансляція неактивна
                        Thread.sleep(50)
                        continue
                    }

                    // Отримуємання кадру з черги frameQueue
                    val frameData = frameQueue.poll(5, TimeUnit.SECONDS) // Очікування кадру максимум 5 сек
                    if (frameData == null) {
                        Log.e("Stream", "Frame queue timeout. No frames available for encoding.")
                        // Якщо кадр не надійшов за 5 секунд,  виключення
                        throw IllegalStateException("No frames in queue for encoding.")
                    }

                    encodeFrame(frameData, onEncodedFrame) // Кодування кадру
                } catch (e: InterruptedException) {  // Обробка переривання потоку
                    Log.e("Stream", "Encoding thread interrupted", e)
                    break
                } catch (e: IllegalStateException) {// Обробка ситуації, коли кадри перестали надходити
                    Log.e("Stream", "Encoding process encountered an issue: ${e.message}")

                } catch (e: Exception) {// Обробка будь-яких інших несподіваних помилок
                    Log.e("Stream", "Unexpected error in encoding loop", e)
                }
            }
        }

    }

    // Кодування кадру
    private fun encodeFrame(frameData: ByteArray, onEncodedFrame: (ByteArray) -> Unit) {
        // Перевірка, чи MediaCodec ініціалізований
        if (mediaCodec == null) {
            Log.e("Stream", "MediaCodec is not initialized. Skipping frame encoding.")
            return
        }

        try {
            Log.d("Stream", "Encoding frame of size: ${frameData.size}")
            // Отримуємо індекс вхідного буфера MediaCodec
            val inputBufferIndex = mediaCodec?.dequeueInputBuffer(-1) ?: -1
            if (inputBufferIndex < 0) {
                // Якщо немає доступного буфера, пропуск кадру
                Log.d("Stream", "MediaCodec input buffer timeout. Skipping frame.")
                return
            }
            // Якщо вхідний буфер доступний
            if (inputBufferIndex >= 0) {
                val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear() // Очистка буфера перед записом даних
                inputBuffer?.put(frameData)

                // Час відображення кадру
                val presentationTimeUs = System.nanoTime() / 1000
                mediaCodec?.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    frameData.size,
                    presentationTimeUs,
                    0 // No special flags
                )
            }
            // Вихідний буфер для закодованих даних
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
            if (outputBufferIndex < 0) {  // Якщо вихідний буфер не доступний, кадр буде закодовано із затримкою
                Log.d("Stream", "MediaCodec output buffer timeout. Frame encoding delayed.")
                return
            }
            // Цикл обробки закодованих буферів
            while (outputBufferIndex >= 0) {
                val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                val outData = ByteArray(bufferInfo.size) // Масив для збереження закодованих даних
                outputBuffer?.get(outData) // Отримання закодованих дані

                Log.d("Stream", "Encoded frame ready to send (${outData.size} bytes).")
                onEncodedFrame(outData) // Передача закодованого кадру через колбек


                //Звільнення буферу після використання
                mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
            }

        } catch (e: Exception) {
            Log.e("Stream", "Error during encoding frame", e)
        }
    }


    // Створення та налаштування MediaCodec
    private fun createMediaCodec(): MediaCodec {
        // Створення кодувальника для формату "video/avc" (H.264)
        val mediaCodec = MediaCodec.createEncoderByType("video/avc")
        // Налаштування формату кодування на основі цільової роздільної здатності
        val format = targetResolution?.let { MediaFormat.createVideoFormat("video/avc", it.width, targetResolution!!.height) }

        // Встановлення параметрів формату
        format?.setInteger(MediaFormat.KEY_BIT_RATE, 2000000) // Бітрейт 2 Мбіт/с
        format?.setInteger(MediaFormat.KEY_FRAME_RATE, 30)// Частота кадрів 30 кадрів/сек
        format?.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)  // Інтервал ключових кадрів (кожні 2 секунди)
        format?.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) // Формат кольору YUV420

        try {
            // Налаштування MediaCodec для кодування
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // Запуск MediaCodec
            mediaCodec.start()
            Log.d("Stream", "MediaCodec successfully created and started.")
        } catch (e: MediaCodec.CodecException) {
            Log.d("Stream", "MediaCodec configuration error: ${e.diagnosticInfo}", e)
            throw e
        }

        return mediaCodec
    }

    // Конвертація ImageProxy у формат NV12
    private fun imageProxyToNV12(imageProxy: ImageProxy, cameraType: String): ByteArray {
        // Отримання інформації про кадр
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees  // Кут повороту кадру
        val width = imageProxy.width // Ширина кадру
        val height = imageProxy.height // Висота кадру

        // Отримання окремих площин кадру (Y, U, V)
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        // Буфери даних площин
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        // Рядкові та піксельні кроки (stride) для площин
        val rowStrideY = yPlane.rowStride
        val rowStrideU = uPlane.rowStride
        val rowStrideV = vPlane.rowStride
        val pixelStrideU = uPlane.pixelStride
        val pixelStrideV = vPlane.pixelStride

        // Створення масиву для NV12
        val nv12 = ByteArray(width * height * 3 / 2)

        // Копіювання площини Y (основний яскравісний канал)
        var pos = 0
        for (row in 0 until height) {
            val yOffset = row * rowStrideY
            yBuffer.position(yOffset)
            yBuffer.get(nv12, pos, width)
            pos += width
        }

        // Копіювання площин U і V
        val uvHeight = height / 2
        for (row in 0 until uvHeight) {
            var uPos = row * rowStrideU
            var vPos = row * rowStrideV
            for (col in 0 until width / 2) {
                // NV12: U потом V
                nv12[pos++] = uBuffer[uPos]
                nv12[pos++] = vBuffer[vPos]

                uPos += pixelStrideU
                vPos += pixelStrideV
            }
        }

        return rotateNV12(nv12, width, height, rotationDegrees, cameraType)

    }

    // Повороту кадру у форматі NV12
    private fun rotateNV12(data: ByteArray, width: Int, height: Int, rotationDegrees: Int, cameraType: String): ByteArray {
        // Якщо поворот не потрібен, повернення початкових даних
        if (rotationDegrees == 0) return data

        val rotated = ByteArray(data.size) // Масив для повернутого кадру
        val frameSize = width * height

        // Встановлення коректного кута повороту для передньої камери
        val actualRotationDegrees = if (cameraType == "Front") {
            when (rotationDegrees) {
                90 -> 270
                180 -> 180
                270 -> 90
                else -> 0
            }
        } else {
            rotationDegrees
        }

        when (actualRotationDegrees) {
            90 -> {
                // Поворот Y на 90 градусів
                for (j in 0 until height) {
                    for (i in 0 until width) {
                        rotated[i * height + (height - j - 1)] = data[j * width + i]
                    }
                }

                // Поворот UV
                val uvWidth = width / 2
                val uvHeight = height / 2

                for (j in 0 until uvHeight) {
                    for (i in 0 until uvWidth) {
                        val uIndex = frameSize + j * width + i * 2
                        val vIndex = uIndex + 1
                        val rotatedUIndex = frameSize + (i * uvHeight + uvHeight - j - 1) * 2
                        val rotatedVIndex = rotatedUIndex + 1

                        if (uIndex < data.size && rotatedUIndex < rotated.size) {
                            rotated[rotatedUIndex] = data[uIndex]
                        }
                        if (vIndex < data.size && rotatedVIndex < rotated.size) {
                            rotated[rotatedVIndex] = data[vIndex]
                        }
                    }
                }
            }

            180 -> {
                // Поворот Y на 180 градусів
                for (j in 0 until height) {
                    for (i in 0 until width) {
                        rotated[(height - j - 1) * width + (width - i - 1)] = data[j * width + i]
                    }
                }

                // Поворот UV
                for (j in 0 until height / 2) {
                    for (i in 0 until width / 2) {
                        val uvIndex = frameSize + j * width + i * 2
                        val rotatedUVIndex = frameSize + (height / 2 - j - 1) * width + (width / 2 - i - 1) * 2

                        if (uvIndex < data.size && rotatedUVIndex < rotated.size) {
                            rotated[rotatedUVIndex] = data[uvIndex]
                            rotated[rotatedUVIndex + 1] = data[uvIndex + 1]
                        }
                    }
                }
            }

            270 -> {
                // Поворот Y на 270 градусів
                for (j in 0 until height) {
                    for (i in 0 until width) {
                        rotated[(width - i - 1) * height + j] = data[j * width + i]
                    }
                }

                // Поворот UV
                for (j in 0 until height / 2) {
                    for (i in 0 until width / 2) {
                        val uvIndex = frameSize + j * width + i * 2
                        val rotatedUVIndex = frameSize + (width / 2 - i - 1) * height / 2 + j * 2

                        if (uvIndex < data.size && rotatedUVIndex < rotated.size) {
                            rotated[rotatedUVIndex] = data[uvIndex]
                            rotated[rotatedUVIndex + 1] = data[uvIndex + 1]
                        }
                    }
                }
            }

            0 -> {
                // Без повороту
                System.arraycopy(data, 0, rotated, 0, data.size)
            }


            else -> Log.e("Stream", "Unsupported rotation: $actualRotationDegrees")
        }


        // Якщо передня камера, додатково розворот на 180 градусів
        return if (cameraType == "Front") rotateNV12By180(rotated, width, height) else rotated
    }

    //Додатковий розворот кадру на 180 градусів
    private fun rotateNV12By180(data: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(data.size)
        val frameSize = width * height

        // Розворот Y
        for (j in 0 until height) {
            for (i in 0 until width) {
                rotated[(height - j - 1) * width + (width - i - 1)] = data[j * width + i]
            }
        }

        // Розворот UV
        for (j in 0 until height / 2) {
            for (i in 0 until width / 2) {
                val uvIndex = frameSize + j * width + i * 2
                val rotatedUVIndex = frameSize + (height / 2 - j - 1) * width + (width / 2 - i - 1) * 2

                rotated[rotatedUVIndex] = data[uvIndex]
                rotated[rotatedUVIndex + 1] = data[uvIndex + 1]
            }
        }

        return rotated
    }


    fun releaseResources() {
        try {
            encodingExecutor.shutdownNow()
            cameraExecutor.shutdownNow()
            frameQueue.clear()
            mediaCodec?.stop()
            mediaCodec?.release()
            cameraProvider?.unbindAll()
            Log.d("Stream", "Camera stopped and resources released.")
        } catch (e: Exception) {
            Log.e("Stream", "Error stopping camera", e)
        }
    }

    fun bindPreview(view: PreviewView) {
        previewView = view
        preview?.setSurfaceProvider(view.surfaceProvider)
    }

    fun setStreamingState(isStreaming: Boolean) {
        this.isStreaming = isStreaming
        Log.d("Stream", "Streaming state set to: $isStreaming")
    }
}



class ConnectionManager {
    private var socket: DatagramSocket? = null // Сокет для UDP-з'єднання
    private var address: InetAddress? = null // IP-адреса сервера
    private var port: Int = 0 // Порт сервера
    var isStreaming: Boolean = false // Стан трансляції (увімкнена/вимкнена)
    private val coroutineScope = CoroutineScope(Dispatchers.IO) // Coroutine Scope для асинхронних операцій

    // Канали для відправки відео та аудіо
    private val videoSendChannel = Channel<ByteArray>(capacity = 10000, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val audioSendChannel = Channel<ByteArray>(capacity = 1000, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        coroutineScope.launch {  // Обробка відео-кадрів з каналу
            videoSendChannel.consumeAsFlow().collect { encodedFrame ->
                sendFrame(encodedFrame, isAudio = false) // Відправка відео-кадрів
            }
        }

        coroutineScope.launch {  // Обробка аудіо-кадрів з каналу
            audioSendChannel.consumeAsFlow().collect { audioFrame ->
                sendFrame(audioFrame, isAudio = true)// Відправка аудіо-кадрів
            }
        }
    }

    private suspend fun sendFrame(data: ByteArray, isAudio: Boolean) {  // Відправка кадру через UDP
        if (isStreaming && socket != null && address != null) {  // Перевірка стану трансляції і підключення
            try {
                // Заголовок: аудіо (0x01) або відео (0x00)
                val header = if (isAudio) byteArrayOf(0x01) else byteArrayOf(0x00)
                // Розмір даних у байтах
                val sizeBytes = byteArrayOf(
                    (data.size shr 24).toByte(),
                    (data.size shr 16).toByte(),
                    (data.size shr 8).toByte(),
                    (data.size and 0xFF).toByte()
                )
                val packetData = header + sizeBytes + data

                if (packetData.size > 65500) { // Перевірка на перевищення ліміту розміру UDP-пакета
                    Log.d("ConnectionManager", "Розмір пакета перевищує ліміт UDP")
                    return
                }
                // Створення  і відправка пакету
                val packet = DatagramPacket(packetData, packetData.size, address, port)
                socket?.send(packet)
                Log.d("ConnectionManager", "${if (isAudio) "Audio" else "Video"} frame sent: ${data.size} bytes")
            } catch (e: Exception) {
                Log.e("ConnectionManager", "Failed to send frame: ${e.message}")
                close() //Закриття з'єднання
            }
        }
    }
    // Відправка аудіо-кадру до каналу
    fun sendAudioFrame(audioFrame: ByteArray) {
        if (!isStreaming) return // Якщо трансляція вимкнена, нічого
        coroutineScope.launch {
            audioSendChannel.trySend(audioFrame).onFailure {
                if (it != null) {
                    Log.e("ConnectionManager", "Failed to enqueue audio frame: ${it.message}")
                }
            }
        }
    }

    // Підключення до сервера
    fun connect(ip: String, port: Int) {
        coroutineScope.launch {
            try {
                this@ConnectionManager.port = port  // Збереження  порта
                this@ConnectionManager.address = InetAddress.getByName(ip)  // Збереження IP
                socket = DatagramSocket()// Ініціалізація сокету
                Log.d("ConnectionManager", "Connected to $ip:$port")
            } catch (e: Exception) {
                Log.e("ConnectionManager", "Connection failed: ${e.message}")
            }
        }
    }

    // Відправка відео-кадру до каналу
    fun sendEncodedFrame(encodedFrame: ByteArray) {
        if (!isStreaming) {
            println("Streaming is disabled. Frame not sent.")
            return
        }

        coroutineScope.launch {
            val result = videoSendChannel.trySend(encodedFrame)
            if (result.isSuccess) {
                println("Frame enqueued for sending.")
            } else {
                println("Failed to enqueue frame: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    // Закриття з'єднання
    fun close() {
        coroutineScope.launch {
            try {
                socket?.close()
                Log.d("ConnectionManager", "Connection closed")
            } catch (e: Exception) {
                Log.e("ConnectionManager", "Error closing connection: ${e.message}")
            }
        }
    }

    // Встановлення стану трансляції
    fun setStreamingState(isStreaming: Boolean) {
        this.isStreaming = isStreaming
        Log.d("ConnectionManager", "Streaming state set to: $isStreaming")
    }

    // Звільнення ресурсів
    fun releaseResources() {
        videoSendChannel.close()
        audioSendChannel.close()
        coroutineScope.cancel()
        Log.d("ConnectionManager", "Resources released")
    }

    // Відправка тригера та очікування відповіді для отримання IP-адреси
    fun sendTriggerAndWaitForResponse(
        triggerWord: String, // Триггерне слово для запиту
        serverIp: String, // IP-адреса сервера
        port: Int, // Порт сервера
        onResponse: (String?) -> Unit // Колбек з отриманою IP-адресою
    ) {
        coroutineScope.launch {
            try {
                val socket = DatagramSocket() // Створення тимчасового сокету
                val sendData = triggerWord.toByteArray() // Перетворення тригерного слова на байти
                val address = InetAddress.getByName(serverIp) // Визначення адреси сервера
                val packet = DatagramPacket(sendData, sendData.size, address, port)
                socket.send(packet) // Відправка пакету
                Log.d("ConnectionManager", "Sent packet to $serverIp:$port")

                val buffer = ByteArray(1024) // Буфер для отримання відповіді
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.soTimeout = 5000

                try {
                    socket.receive(responsePacket) // Очікування відповіді
                    val responseMessage = String(responsePacket.data, 0, responsePacket.length) // Перетворення відповіді у рядок
                    Log.d("ConnectionManager", "Response received: $responseMessage from ${responsePacket.address.hostAddress}")

                    withContext(Dispatchers.Main) {
                        onResponse(responsePacket.address.hostAddress) // Повертаємо IP-адресу через колбек
                    }
                } catch (e: Exception) {
                    Log.e("ConnectionManager", "No response received: ${e.message}")
                    withContext(Dispatchers.Main) {
                        onResponse(null) //Якщо відповіді немає
                    }
                } finally {
                    socket.close() // Закриття тимчасового сокету
                }
            } catch (e: Exception) {
                Log.e("ConnectionManager", "Network error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResponse(null) // У разі помилки
                }
            }
        }
    }
}










