import asyncio
import socket
import struct
from aiohttp import web
from pymongo import MongoClient
import hashlib
import os


# Клас для управління базою даних MongoDB
class DBManager:
    def __init__(self, db_uri, db_name):
        # Підключення до MongoDB
        self.client = MongoClient(db_uri)
        self.db = self.client[db_name]
        self.users_collection = self.db["user"]

    # Реєстрація користувача
    async def register_user(self, login, password, email, is_premium=False):
        print(f"Attempting to register user with login: {login}, email: {email}, is_premium: {is_premium}")
        if not self.is_email_valid(email):  # Перевірка формату email
            print(f"Invalid email format: {email}")
            return "Invalid email format"

        if self.users_collection.find_one({"Login": login}):# Перевірка, чи існує логін
            print(f"User with login {login} already exists.")
            return "User with this login already exists"

        if self.users_collection.find_one({"Email": email}): # Перевірка, чи існує email
            print(f"User with email {email} already exists.")
            return "User with this email already exists"

        salt = os.urandom(16).hex() # Генерація випадкової "солі"
        print(f"Generated salt: {salt}")
        # Хешування пароля з доданою сіллю
        salted_hashed_password = hashlib.sha256((password + salt).encode()).hexdigest()
        print(f"Salted hash (SHA256 of password + salt): {salted_hashed_password}")

        # Створення нового користувача
        new_user = {
            "Login": login,
            "Password": salted_hashed_password,
            "Salt": salt,
            "Email": email,
            "Is_Premium": is_premium
        }
        # Додавання нового користувача в базу даних
        self.users_collection.insert_one(new_user)
        print(f"User {login} registered successfully.")
        return "User registered successfully"

    # Перевірка валідності email
    def is_email_valid(self, email):
        import re
        email_regex = r'^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$'
        return re.match(email_regex, email) is not None

    # Метод для входу користувача
    async def login_user(self, login, password):
        print(f"Attempting to log in user with login: {login}")

        # Пошук користувача в БД за логіном
        user = self.users_collection.find_one({"Login": login})
        if user:
            print(f"User found in database: {login}")

            # Отримання солі із бази
            salt = user["Salt"]
            print(f"Retrieved salt from database: {salt}")

            # Повторення хешування
            salted_hashed_password = hashlib.sha256((password + salt).encode()).hexdigest()
            print(f"Computed salted hash for login: {salted_hashed_password}")

            # Порівняння хешованого паролю з тим що в базі
            if user["Password"] == salted_hashed_password:
                print(f"Login successful for user: {login}")
                return {"message": "Login successful", "isPremium": user["Is_Premium"]}

            print("Password mismatch.")
            return {"message": "WrongPassOrLogin", "isPremium": False}

        print("User not found.")
        return {"message": "WrongPassOrLogin", "isPremium": False}

    # Покупка преміум-доступу
    async def buy_premium(self, login):
        print(f"Attempting to upgrade user {login} to premium.")
        # Пошук користувача в БД за логіном
        user = self.users_collection.find_one({"Login": login})
        if user:# Оновлюємо статус користувача до преміум
            self.users_collection.update_one({"Login": login}, {"$set": {"Is_Premium": True}})
            print(f"User {login} upgraded to premium.")
            return {"login": login, "isPremium": True}

        print(f"User {login} not found.")
        return {"message": "User not found"}


async def log_request(request):
    print(f"Incoming {request.method} request to {request.path}")
    if request.method in ("POST", "PUT"):
        try:
            data = await request.json()
            print(f"Request JSON data: {data}")
        except Exception as e:
            print(f"Failed to parse JSON data: {e}")
    return None


@web.middleware
async def request_logger_middleware(request, handler):
    await log_request(request)
    response = await handler(request)
    print(f"Outgoing response: {response.status}, {response.text if hasattr(response, 'text') else 'No Text Content'}")
    return response

#Реєстрація
async def handle_register(request):
    try:
        # Розбір JSON із тіла запиту
        data = await request.json()

        print(f"Received registration data: {data}")

        login = data.get("login")
        password = data.get("password")
        email = data.get("email")

        if not login or not password or not email:
            print("Missing fields in JSON")
            return web.Response(text="Missing fields in JSON", status=400)

        # Реєстрація користувача через DBManager
        response = await request.app["db_manager"].register_user(login, password, email)
        print(f"Registration response: {response}")

        # Повернення відповідного статусу залежно від результату
        if response == "User registered successfully":
            return web.Response(text=response, status=200)
        elif response == "Invalid email format":
            return web.Response(text=response, status=400)
        elif response == "User with this login already exists":
            return web.Response(text=response, status=409)
        elif response == "User with this email already exists":
            return web.Response(text=response, status=409)
        else:
            return web.Response(text="Unknown error during registration", status=500)

    except Exception as e:
        error_message = f"Error during registration: {str(e)}"
        print(error_message)
        return web.Response(text=error_message, status=500)

#Логін
async def handle_login(request):
    try:
        data = await request.json()

        print(f"Received login data: {data}")

        login = data.get("login")
        password = data.get("password")

        if not login or not password:
            print("Missing login or password in JSON")
            return web.json_response({"message": "Missing fields in JSON", "isPremium": False}, status=400)

        # Вхід користувача через DBManager
        response = await request.app["db_manager"].login_user(login, password)
        print(f"Login response from DBManager: {response}")

        return web.json_response({
            "message": response["message"],
            "isPremium": response.get("isPremium", False)
        }, status=200 if response["message"] == "Login successful" else 401)
    except Exception as e:
        error_message = f"Error during login: {str(e)}"
        print(error_message)
        return web.json_response({"message": error_message, "isPremium": False}, status=500)


async def handle_buy_premium(request):
    try:
        # Розбір JSON
        data = await request.json()

        print(f"Received buy premium request: {data}")

        login = data.get("login")

        if not login:
            print("Missing login in JSON")
            return web.json_response({"message": "Missing fields in JSON"}, status=400)

        # Оновлення статусу через DBManager
        response = await request.app["db_manager"].buy_premium(login)
        print(f"Buy premium response from DBManager: {response}")

        return web.json_response(response, status=200 if "isPremium" in response else 404)
    except Exception as e:
        error_message = f"Error during buy premium: {str(e)}"
        print(error_message)
        return web.json_response({"message": error_message}, status=500)


# Функція для обробки mDNS-запитів
async def mdns_listener():
    MDNS_GROUP = "224.0.0.251"
    MDNS_PORT = 8092

    mdns_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    mdns_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    mdns_socket.bind(("0.0.0.0", MDNS_PORT))

    mreq = struct.pack("4sl", socket.inet_aton(MDNS_GROUP), socket.INADDR_ANY)
    mdns_socket.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)

    print(f"mDNS listener started on {MDNS_GROUP}:{MDNS_PORT}")

    loop = asyncio.get_event_loop()

    while True:
        try:
            data, addr = await loop.run_in_executor(None, mdns_socket.recvfrom, 1024)
            message = data.decode('utf-8')
            print(f"Received mDNS message: '{message}' from {addr}")

            if "LocateHOST" in message:
                response = f"HOST_FOUND: {addr[0]}:{MDNS_PORT}"
                print(f"Preparing to send response: '{response}' to {addr}")
                await loop.run_in_executor(None, mdns_socket.sendto, response.encode('utf-8'), addr)
                print(f"Sent mDNS response: '{response}' to {addr}")

        except Exception as e:
            print(f"Error in mDNS listener: {e}")

# Запуск сервера
async def start_server():
    app = web.Application(middlewares=[request_logger_middleware])
    db_uri = "mongodb+srv://LocalChat:BQUP3nbljOEbCoHy@cluster0.9x2gr.mongodb.net/?retryWrites=true&w=majority"
    db_name = "PhoneWebcam"

    db_manager = DBManager(db_uri, db_name)
    app["db_manager"] = db_manager

    app.router.add_post("/register", handle_register)
    app.router.add_post("/login", handle_login)
    app.router.add_post("/buy_premium", handle_buy_premium)

    asyncio.create_task(mdns_listener())

    print("Server started on http://0.0.0.0:7771")
    return app


if __name__ == "__main__":
    web.run_app(start_server(), host="0.0.0.0", port=7771)
