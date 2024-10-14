import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.awt.Image
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Клас для обробки USB-з'єднання
class USBHandler(private val port: Int, private val uiManager: UIManager) {
    private var serverSocket: ServerSocket? = null

    // Запуск сервера для прийому зображень через USB
    fun start() {
        Thread {
            try {
                // Створення  сокету на вказаному порту
                serverSocket = ServerSocket(port)
                println("USB Server started on port $port")

                while (true) {// Постійне очікування клієнтських підключень
                    try {
                        val socket = serverSocket?.accept()// Прийняття нового клієнтського з'єднанн
                        println("USB Client connected from ${socket?.inetAddress?.hostAddress}")
                        val inputStream = DataInputStream(socket?.getInputStream())

                        while (!socket?.isClosed!!) {// Читання даних з сокету, поки з'єднання відкрите
                            val size = inputStream.readInt()// Читання розміру зображення
                            println("USB Received image size: $size bytes")

                            if (size > 0) {// Якщо розмір дійсний, отримуємо зображення
                                val imageBytes = ByteArray(size)
                                inputStream.readFully(imageBytes)// Читання байтів зображення
                                val image = ImageIO.read(ByteArrayInputStream(imageBytes))// Декодування зображення

                                if (image != null) {// Якщо зображення успішно отримано
                                    println("USB Image received and decoded")
                                    SwingUtilities.invokeLater {
                                        uiManager.updateImage(image)// Оновлення зображення в UI
                                    }
                                } else {
                                    println("USB Failed to decode image.")
                                }
                            } else {
                                println("USB Invalid image size received.")
                            }
                        }
                        // Закриття з'єднання після завершення
                        socket.close()
                        println("USB Client disconnected")
                    } catch (e: SocketException) {
                        println("USB Connection error: ${e.message}")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}



//Клас для обробки стандартних з'єднань через Wi-Fi
class SocketServer(private val port: Int, private val uiManager: UIManager) {
    private var serverSocket: ServerSocket? = null

    fun start() {// Запуск сервера
        Thread {
            try {
                serverSocket = ServerSocket(port)// Створення серверного сокету на вказаному порту
                val localIp = InetAddress.getLocalHost().hostAddress// Отримання локальної IP-адреси сервера
                println("Server started on IP: $localIp, waiting for connection on port $port...")

                while (true) {// Безперервне очікування нових клієнтських підключень
                    try {
                        // Прийняття нового клієнтського з'єднання
                        val socket = serverSocket?.accept()
                        if (socket != null) {
                            println("Client connected from ${socket.inetAddress.hostAddress}")
                            val clientHandler = ClientHandler(socket, uiManager)// Створення обробника клієнта
                            clientHandler.handleClient()// Обробка підключеного клієнта
                        }
                    } catch (e: Exception) {
                        println("Connection error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}

// Обробник клієнта для Wi-Fi з'єднань
class ClientHandler(private val socket: Socket, private val uiManager: UIManager) {
    fun handleClient() { // Функція для обробки клієнтського з'єднання
        Thread {
            try {
                val inputStream = DataInputStream(socket.getInputStream()) // Отримання вхідного потоку даних
                while (!socket.isClosed) {// Поки з'єднання не закрито
                    val size = inputStream.readInt()// Читання розміру зображення
                    println("Wi-Fi Received image size: $size bytes")

                    if (size > 0) {
                        val imageBytes = ByteArray(size)
                        inputStream.readFully(imageBytes)// Повне читання даних зображення

                        // Логирование первых байтов для анализа формата данных
                        println("Wi-Fi First 10 bytes: ${imageBytes.take(10).joinToString(", ")}")

                        val image = ImageIO.read(ByteArrayInputStream(imageBytes))// Перетворення байтів у зображення

                        if (image != null) {
                            println("Wi-Fi Image received and decoded successfully")
                            SwingUtilities.invokeLater {
                                uiManager.updateImage(image)// Оновлення зображення у графічному інтерфейсі
                            }
                        } else {
                            println("Wi-Fi Failed to decode image. First bytes of data: ${imageBytes.take(10).joinToString(", ")}")
                        }
                    } else {
                        println("Wi-Fi Invalid image size received.")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    socket.close()// Закриття сокету після завершення роботи
                    println("Wi-Fi Client disconnected")
                } catch (e: Exception) {
                    println("Wi-Fi Error closing socket: ${e.message}")
                }
            }
        }.start()
    }
}

//Клас для створення та керування інтерфейсом для відображення відео
class UIManager {
    private val frame: JFrame = JFrame("Webcam Stream")// Вікно для відображення потоку з камери
    private val label: JLabel = JLabel()
    private var currentImage: Image? = null// Поточне зображення
    private var rotationAngle: Double = 0.0// Кут повороту зображення

    // Створення графічного інтерфейсу
    fun createUI(ipAddress: String) {
        val panel = JPanel()// Панель для кнопок
        val clockwiseButton = JButton("↻")
        val counterClockwiseButton = JButton("↺")

        // Обробники натискання на кнопки повороту
        clockwiseButton.addActionListener { rotateImage(90.0) }
        counterClockwiseButton.addActionListener { rotateImage(-90.0) }

        // Додавання кнопок повороту на панель
        panel.add(clockwiseButton)
        panel.add(counterClockwiseButton)

        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE// Закриття програми при закритті вікна
        frame.contentPane.add(label)
        frame.contentPane.add(panel, "South")// Додавання панелі з кнопками в нижню частину вікна
        frame.setSize(640, 480)
        frame.isVisible = true

        label.text = "Server IP: $ipAddress, waiting for connection..."
    }


    // Оновлення зображення на екрані
    fun updateImage(image: Image) {
        currentImage = image// Збереження нового зображення
        applyRotation()// Застосування поточного кута повороту
    }

    // Поворот зображення на заданий кут
    private fun rotateImage(angle: Double) {
        rotationAngle += Math.toRadians(angle) // Додавання кута до поточного кута повороту
        applyRotation()// Поворот
    }

    // Застосування обертання до зображення
    private fun applyRotation() {
        if (currentImage != null) { // Перевірка, чи є зображення
            val rotatedImage = rotate(currentImage!!, rotationAngle) // Поворот зображення на поточний кут
            label.icon = ImageIcon(rotatedImage)
            frame.repaint() // Оновлення вікна для відображення нового зображення
        }
    }

    // Метод для обертання зображення на вказаний кут
    private fun rotate(img: Image, angle: Double): Image {
        val icon = ImageIcon(img) // Створення іконку з зображення
        val width = icon.iconWidth// Отримання ширини зображення
        val height = icon.iconHeight// Отримання висоти зображення

        // Створення порожного зображення для обертання
        val rotatedImg = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g2d = rotatedImg.createGraphics() // Отримання контексту графіки

        g2d.rotate(angle, (width / 2).toDouble(), (height / 2).toDouble())  // Здійснення обертання зображення на вказаний кут
        g2d.drawImage(img, 0, 0, null) // Створення повернутого зображення
        g2d.dispose() // Завершення роботи з графікою

        return rotatedImg
    }
}

// Виконання ADB команд
fun executeADBCommand(command: String) {
    try {
        // Створення процесу для виконання ADB команди через командний рядок
        val process = ProcessBuilder("cmd.exe", "/c", command)
            .redirectErrorStream(true) // Об'єднання потоків стандартного виведення і помилок
            .start()
        val reader = process.inputStream.bufferedReader()
        val output = reader.use { it.readText() } // Читання результат виконання команди
        println(output) // Виведення результату в консоль
    } catch (e: Exception) {
        println("Error executing ADB command: ${e.message}")
    }
}


fun main() {
    // Виконання ADB команди для перенаправлення трафіку на порт 8086
    executeADBCommand("adb reverse --remove-all")// Видалення  всіх існуючих перенаправлень
    executeADBCommand("adb reverse tcp:8086 tcp:8086") // Налаштування перенаправлення порту 8086

    val uiManager = UIManager()// Створення  екземпляру UIManager для роботи з інтерфейсом

    // Запуск 2 серверів: для стандартних підключень через мережу, інший для USB-з'єднань
    val socketServer = SocketServer(8090, uiManager)
    socketServer.start()

    val usbServer = USBHandler(8086, uiManager)
    usbServer.start()

    uiManager.createUI("localhost") // Створення інтерфейсу і виведення інформації про сервер (localhost)
}
}
