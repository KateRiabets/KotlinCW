
package com.example.phonewebcam
import androidx.compose.ui.focus.FocusDirection

import androidx.compose.ui.text.input.PasswordVisualTransformation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.activity.compose.BackHandler
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors





class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var connectionManager: ConnectionManager
    private lateinit var cameraManager: CameraManager
    private var currentConnectionType = ConnectionType.WIFI
    private var currentResolution: Size = Size(1280, 720)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ініціалізація обробника для роботи з камерою в окремому потоці
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Ініціалізація  керування підключенням Wi-Fi/USB
        connectionManager = ConnectionManager(cameraExecutor, isWifi = currentConnectionType == ConnectionType.WIFI)
        // Ініціалізація cameraManager , який передає кадри для відправки через підключення
        cameraManager = CameraManager(cameraExecutor) { bitmap ->
            connectionManager.sendVideoFrame(bitmap)
        }

        setContent {
            var isAuthenticated by remember { mutableStateOf(false) } // Чи користувач аутентифікований
            var isRegistration by remember { mutableStateOf(false) } // Реєстрація/авторизація
            var isAuthFormVisible by remember { mutableStateOf(false) }  // Чи показується форма авторизації/реєстрації
            var isWifiSetup by remember { mutableStateOf(false) } // Чи обрано Wi-Fi підключення
            var isUsbSetup by remember { mutableStateOf(false) }// Чи обрано USB підключення
            var isConnected by remember { mutableStateOf(false) } // Чи встановлено підключення


            when {
                // Якщо користувач не аутентифікований і форма авторизації/реєстрації не показана
                !isAuthenticated && !isAuthFormVisible -> AuthScreen(
                    onActionClick = { isRegister ->
                        isRegistration = isRegister //Визначення, це реєстрація чи авторизація
                        isAuthFormVisible = true // Показ форми для введення даних
                    },
                    onSkipClick = {
                        isAuthenticated = true // Пропуск авторизації і реєстрації коли "skip for now"
                    }
                )

                // Якщо показано форму авторизації
                isAuthFormVisible -> AuthFormScreen(
                    isRegistration = isRegistration,
                    onFormSubmit = {
                        isAuthenticated = true // Успішна авторизація/реєстрація
                        isAuthFormVisible = false// Приховти форму
                    },
                    onBackClick = {
                        isAuthFormVisible = false // Повернутися на попередній екран
                    }
                )

                // Якщо аутентифікація пройдена, але користувач не обрав тип підключення
                !isWifiSetup && !isUsbSetup -> WifiOrUsbSetupScreen(
                    onConnectionTypeSelected = { isWifi ->
                        // Встановити тип підключення (Wi-Fi або USB)
                        currentConnectionType = if (isWifi) ConnectionType.WIFI else ConnectionType.USB
                        isWifiSetup = isWifi
                        isUsbSetup = !isWifi
                        // Ініціалізація менеджера підключення для вибраного типу
                        connectionManager = ConnectionManager(cameraExecutor, isWifi)
                    },
                    onExitClick = {
                        // На екран авторизації
                        isAuthenticated = false
                        isAuthFormVisible = false
                    }
                )

                // Якщо встановлено  Wi-Fi або USB підключення
                isWifiSetup || isUsbSetup -> ConnectionScreen(
                    isWifi = currentConnectionType == ConnectionType.WIFI,
                    onBackClick = {
                        isWifiSetup = false
                        isUsbSetup = false
                    },
                    onConnectClick = { ip ->
                        if (currentConnectionType == ConnectionType.WIFI) {
                            connectionManager.connectToServer(ip, 8090) // IP требя для Wi-Fi
                        } else {
                            connectionManager.connectToServer() // Для USB IP не треба
                        }
                        isConnected = true
                    }
                )
            }
        }
    }



    val customFontFamily = FontFamily(
        Font(R.font.nunito, FontWeight.Normal)
    )

    @Composable
    fun WifiOrUsbSetupScreen(
        onConnectionTypeSelected: (Boolean) -> Unit, // Колбек для вибору типу підключення (Wi-Fi або USB)
        onExitClick: () -> Unit // Колбек для виходу з акаунту
    ) {

        MaterialTheme {

            Column(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Реклама
                Button(
                    onClick = { /* Тимчасово не виконує жодної дії */ },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(66.dp),
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xffa5a5a5))
                ) {
                    Text(
                        "Тут могла бути ваша реклама",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = customFontFamily
                    )
                }

                // Привітальне повідомлення
                Text(
                    text = "Вітаю! Ви ввійшли як гість.",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp),
                    fontFamily = customFontFamily,
                    color = Color(0xffde9fb8)
                )

                // Кнопка для вибору підключення через Wi-Fi
                Button(
                    onClick = { onConnectionTypeSelected(true) }, // Виклик колбеку з параметром true(вибір Wi-Fi)
                    modifier = Modifier
                        .padding(5.dp)
                        .fillMaxWidth(0.80f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xffffb3d2))
                ) {
                    Text(
                        "Connect via Wi-Fi",
                        color = Color.White,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = customFontFamily
                    )
                }

                // Кнопка для вибору підключення через USB
                Button(
                    onClick = { onConnectionTypeSelected(false) }, // Виклик колбеку з параметром false( вибір USB)
                    modifier = Modifier
                        .fillMaxWidth(0.80f)
                        .padding(5.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xfff9a3b6))
                ) {
                    Text(
                        "Connect via USB",
                        color = Color.White,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = customFontFamily
                    )
                }

                // Кнопка для виходу з акаунту
                Button(
                    onClick = { onExitClick() }, // Виклик колбеку для виходу
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .padding(5.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xffd77aa7))
                ) {
                    Text(
                        "Вийти з акаунту",
                        color = Color.White,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = customFontFamily
                    )
                }

                // Кнопка для придбання Premium
                Button(
                    onClick = { /* Тимчасово нічого не робить */ },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .padding(5.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xffb38dc8))
                ) {
                    Text(
                        "Premium",
                        color = Color.White,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = customFontFamily
                    )
                }
            }
        }
    }




    @Composable
    fun AuthFormScreen(
        isRegistration: Boolean, // Чи це екран реєстрації (true) або входу (false)
        onFormSubmit: () -> Unit, // Колбек для дії при натисканні кнопки реєстрації або входу
        onBackClick: () -> Unit // Колбек для дії при натисканні на кнопку "Назад"
    ) {
        var login by remember { mutableStateOf("") } // Поле для зберігання логіну
        var password by remember { mutableStateOf("") } // Поле для зберігання пароля
        var email by remember { mutableStateOf("") } // Поле для зберігання email (тільки для реєстрації)
        var confirmPassword by remember { mutableStateOf("") } // Поле для підтвердження пароля (тільки для реєстрації)
        val focusManager = LocalFocusManager.current // Менеджер фокуса для контролю клавіатури

        // Обробник системної кнопки "Назад"
        BackHandler {
            onBackClick() // Повертає на попередній екран при натисканні на кнопку "Назад"
        }


        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clickable { focusManager.clearFocus() }, // Закриває клавіатуру при кліку поза полем вводу
            horizontalAlignment = Alignment.CenterHorizontally, // Вирівнювання по горизонталі
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            //Логотип
            Image(
                painter = painterResource(id = R.drawable.lol_logo),
                contentDescription = "Logo",
                modifier = Modifier.size(150.dp),
                contentScale = ContentScale.Fit // Масштабування без обрізання
            )

            // Поле для вводу email, відображається тільки при реєстрації
            if (isRegistration) {
                OutlinedTextField(
                    value = email, // Поточне значення email
                    onValueChange = { email = it }, // Оновлення email при зміні тексту
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Next), // Встановлення "Далі" для клавіатури
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }) // Перехід на наступне поле при натисканні "Далі"
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Поле для вводу логіну
            OutlinedTextField(
                value = login, // Поточне значення
                onValueChange = { login = it }, // Оновлення логіну при зміні тексту
                label = { Text("Login") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Next), // ВСтановлення "Далі" для клавіатури
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }) // Перехід на наступне поле
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Поле для вводу пароля
            OutlinedTextField(
                value = password, // Поточне значення
                onValueChange = { password = it }, // Оновлення пароля при зміні тексту
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Next), // Установка "Далі" для клавіатури
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }), // Перехід на наступне поле
                visualTransformation = PasswordVisualTransformation() //  Приховування введених символів пароля
            )

            Spacer(modifier = Modifier.height(8.dp)) // Відступ між елементами

            // Поле для підтвердження пароля, відображається тільки при реєстрації
            if (isRegistration) {
                OutlinedTextField(
                    value = confirmPassword, // Поточне значення
                    onValueChange = { confirmPassword = it }, // Оновлення підтвердження пароля при зміні тексту
                    label = { Text("Confirm Password") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), // Закриття клавіатури при завершенні вводу
                    visualTransformation = PasswordVisualTransformation() // Приховування введених символів пароля
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(15.dp))

            // Кнопка для відправки форми
            Button(
                onClick = onFormSubmit,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .padding(5.dp),
                colors = ButtonDefaults.buttonColors(Color(0xffffb3d2)) // Колір кнопки
            ) {

                Text(if (isRegistration) "Sign up" else "Log in", fontFamily = customFontFamily, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
        }
    }





    @Composable
    fun ConnectionScreen(
        isWifi: Boolean, // Чи підключення по Wi-Fi
        onBackClick: () -> Unit, // Викликається при натисканні на "назад"
        onConnectClick: (String?) -> Unit // Викликається при підключенні, передає IP для Wi-Fi або null для USB
    ) {
        var ipAddress by remember { mutableStateOf("192.168.0.105") } // Стандартна IP-адреса для Wi-Fi
        var previewView: PreviewView? by remember { mutableStateOf(null) } // Змінна для камери Preview
        var activeResolution by remember { mutableStateOf<Size?>(null) } // Змінна для поточної роздільної здатності
        val focusManager = LocalFocusManager.current // Менеджер фокуса для закриття клавіатури
        val context = LocalContext.current as ComponentActivity // Контекст активності

        // Обробка системної кнопки "Назад"
        BackHandler {
            onBackClick() // Повернення на попередній екран при натисканні кнопки "Назад"
        }

        // Ефект для запуску камери при створенні PreviewView
        LaunchedEffect(previewView) {
            previewView?.let {
                cameraManager.startCamera(context, it) // Запуск камери, якщо PreviewView не null
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()


        ) {
            // Реклама
            Button(
                onClick = { /* Нічого не робить */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xffa5a5a5))
            ) {
                Text("Тут могла бути ваша реклама", fontSize = 21.sp, fontWeight = FontWeight.Bold, fontFamily = customFontFamily) // Текст на кнопці
            }

            Spacer(modifier = Modifier.height(60.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                if (isWifi) {
                    // Поле для вводу IP-адреси, якщо підключення по Wi-Fi
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it }, // Оновлення IP-адреси
                        label = { Text("IP Address") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                    )
                }

                // Кнопка для запуску стріму
                Button(
                    onClick = {
                        if (isWifi) {
                            onConnectClick(ipAddress) // Для Wi-Fi відправляємо IP
                        } else {
                            onConnectClick(null) // Для USB IP не потрібен
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth() // Займає всю ширину
                        .padding(top = 16.dp), // Відступ зверху
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xffffb3d2))
                ) {
                    // Текст на кнопці, що змінюється в залежності від типу підключення (Wi-Fi чи USB)
                    Text(if (isWifi) "Start Wi-Fi Stream" else "Start USB Stream", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, fontFamily = customFontFamily)
                }
            }

            // Блок для відображення потоку з камери та керування роздільною здатністю
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .padding(10.dp)
            ) {
                // Прев'ю зображення з камери
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    AndroidView(factory = { context -> // AndroidView для відображення PreviewView
                        PreviewView(context).apply {
                            previewView = this // збереження PreviewView
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        }
                    }, modifier = Modifier
                        .fillMaxSize() //
                        .background(Color.Gray))
                }


                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    // Кнопка для перемикання камери
                    Button(
                        onClick = {
                            cameraManager.switchCamera() // Перемиканння камери (передня/задня)
                            previewView?.let {
                                cameraManager.startCamera(context, it) // Перезапуск камери
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA5A5A5))
                    ) {
                        Text("⤾", fontSize = 16.sp)
                    }

                    // Роздільна здатність 480p
                    Button(
                        onClick = {
                            currentResolution = Size(640, 480) // Встановлення розміру  640x480
                            activeResolution = currentResolution
                            previewView?.let {
                                cameraManager.setResolution(Size(640, 480)) // Задання розміру
                                cameraManager.startCamera(context, it) // Перезапуск  камери з новою роздільною здатністю
                                                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (activeResolution == Size(640, 480)) Color(0xffffb3d2) else Color(0xFFA5A5A5).copy(alpha = 0.75f) // Активна кнопка має рожевий колір, інші - сірий
                        )
                    ) {
                        Text("480p", fontSize = 16.sp) // Текст кнопки
                    }

                    //Роздільна здатність 720p
                    Button(
                        onClick = {
                            currentResolution = Size(1280, 720) // Встановлення розміру 1280x720
                            activeResolution = currentResolution
                            previewView?.let {
                                cameraManager.setResolution(Size(1280, 720)) // Задання розміру
                                cameraManager.startCamera(context, it) // Перезапуск  камери з новою роздільною здатністю
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (activeResolution == Size(1280, 720)) Color(0xffffb3d2) else Color(0xFFA5A5A5).copy(alpha = 0.75f) // Активна кнопка має рожевий колір, інші - сірий
                        )
                    ) {
                        Text("720p", fontSize = 16.sp)
                    }
                }
            }
        }
    }




    @Composable
    fun AuthScreen(onActionClick: (Boolean) -> Unit, onSkipClick: () -> Unit) {
        MaterialTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 100.dp)
                ) {
                    // Логотип
                    Image(
                        painter = painterResource(id = R.drawable.lol_logo),
                        contentDescription = "Logo",
                        modifier = Modifier.size(150.dp),
                        contentScale = ContentScale.Fit
                    )

                    // Кнопка для реєстрації
                    Button(
                        onClick = { onActionClick(true) }, // При натисканні перехід до реєстрації (true)
                        modifier = Modifier.fillMaxWidth(0.85f).padding(5.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xffffb3d2 ))
                    ) {
                        Text("Sign up", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, fontFamily = customFontFamily)
                    }

                    // Кнопка для авторизації
                    Button(
                        onClick = { onActionClick(false) }, // При натисканні перехід до авторизації (false)
                        modifier = Modifier.fillMaxWidth(0.85f).padding(5.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xfff9a3b6))
                    ) {
                        Text("Log in", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, fontFamily = customFontFamily)
                    }


                    Text(
                        text = "Skip for now",
                        color = Color.Gray,
                        fontSize = 16.sp,
                        modifier = Modifier.clickable { onSkipClick() }  // Пропуск авторизації при натисканні
                    )
                }
            }
        }
    }





    enum class ConnectionType {
        WIFI, USB
    }
}



// Робота з  камерою, захоплення кадрів та їх обробка
class CameraManager(private val executor: ExecutorService, private val onFrameCaptured: (Bitmap) -> Unit) {

    private var isBackCamera = true // Задня камера (true) чи фронтальна (false)
    // Роздільна здатність за замовчуванням
    private var targetResolution: Size = Size(1280, 720)


    // Запуск камери з передачею  кадрів до previewView
    fun startCamera(activity: ComponentActivity, previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()// Налаштування попереднього перегляду відеопотоку

            val imageAnalyzer = ImageAnalysis.Builder() // Налаштування аналізу кадрів для обробки кадрів
                .build()
                .also {
                    it.setAnalyzer(executor, ImageAnalysis.Analyzer { imageProxy ->
                        // Перетворення захопленого кадру у Bitmap
                        val bitmap = imageProxy.toBitmap()
                        // Зміна розміру відповідно до targetResolution
                        val scaledBitmap = scaleBitmapWithAspectRatio(bitmap, targetResolution)
                        onFrameCaptured(scaledBitmap)
                        imageProxy.close() // Закриття кадру
                    })
                }

            // Вибір камери залежно від значення isBackCamera
            val cameraSelector = if (isBackCamera) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else{
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            try {
                cameraProvider.unbindAll()// Зупинка  всіх поточних сесій камери
                // Прив'язка камери до життєвого циклу активності та зв'язок її з превью і аналізом кадрів
                cameraProvider.bindToLifecycle(activity, cameraSelector, preview, imageAnalyzer)
                preview.setSurfaceProvider(previewView.surfaceProvider)
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(activity)) // Використання головного потоку для обробки завдань
    }

    fun setResolution(size: Size) { // Задання бажаної роздільної здатності
        targetResolution = size
    }

    fun switchCamera() {   // Перемикання між фронтальною та задньою камерами
        isBackCamera = !isBackCamera
    }

    // Масштабування зображення Bitmap відповідно до заданої роздільної здатності targetSize, зберігаючи пропорції
    private fun scaleBitmapWithAspectRatio(bitmap: Bitmap, targetSize: Size): Bitmap {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()// Розрахунок співвідношення сторін
        val newWidth = if (bitmap.width > bitmap.height) targetSize.width else (targetSize.height * aspectRatio).toInt()
        val newHeight = if (bitmap.width > bitmap.height) (targetSize.width / aspectRatio).toInt() else targetSize.height
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)// Масштабуємо зображення до нових розмірів
    }
}

// Перетворення ImageProxy у Bitmap
fun ImageProxy.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer // Y-канал
    val uBuffer = planes[1].buffer // U-канал
    val vBuffer = planes[2].buffer // V-канал

    val ySize = yBuffer.remaining() // Розмір Y-даних
    val uSize = uBuffer.remaining() // Розмір U-даних
    val vSize = vBuffer.remaining() // Розмір V-даних

    //Перетворення YUV-дані у формат NV21
    val nv21 = ByteArray(ySize + uSize + vSize)

    // U і V канали змінені місцями
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    // Створення YuvImage з даних NV21
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    // Стискання YUV-даних у формат JPEG для створення зображення
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, out)
    val imageBytes = out.toByteArray()

    // Перетворення масиву  байтів у Bitmap
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}


//Надсилання кадрів через Wi-Fi або USB залежно від вибраного типу підключення
class ConnectionManager(private val executor: ExecutorService, private val isWifi: Boolean) {

    private var socket: Socket? = null // Сокет для встановлення з'єднання з сервером
    private var outputStream: OutputStream? = null  // Потік для надсилання даних

    fun connectToServer(ip: String? = null, port: Int? = null) {// Підключення до сервера через Wi-Fi або USB
        executor.execute {
            try {
                if (isWifi) {
                    // Підключення по Wi-Fi, IP  передаються, порт 8090
                    socket = Socket(ip, port ?: 8090)
                    Log.d("ConnectionManager", "Connected to server via Wi-Fi at $ip:$port")
                } else {
                    // Підключення по USB, IP і порт 8086 фіксовані
                    socket = Socket("127.0.0.1", 8086)
                    Log.d("ConnectionManager", "Connected to server via USB on port 8086")
                }
                outputStream = socket?.getOutputStream()// Відкриття потоку для надсилання даних
            } catch (e: Exception) {
                Log.e("ConnectionManager", "Error connecting to server", e)
            }
        }
    }
    // Надсилання кадру відео
    fun sendVideoFrame(bitmap: Bitmap) {
        executor.execute {
            try {// Якщо потік  відкритий
                outputStream?.let {
                    // Перетворення Bitmap у масив байтів у форматі JPEG
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val byteArray = stream.toByteArray()

                    // Відправка розміру кадру у 4 байтах
                    val size = byteArray.size
                    it.write(size shr 24 and 0xFF)
                    it.write(size shr 16 and 0xFF)
                    it.write(size shr 8 and 0xFF)
                    it.write(size and 0xFF)

                    // Відправка кадру
                    it.write(byteArray)
                    it.flush() //Очистка буферу
                    Log.d("ConnectionManager", "Frame sent successfully!")
                }
            } catch (e: Exception) {
                Log.e("ConnectionManager", "Error sending frame", e)
            }
        }
    }
    // Закриття підключення до сервера
    fun closeConnection() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e("ConnectionManager", "Error closing connection", e)
        }
    }
}
