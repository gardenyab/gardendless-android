# Gardendless для Android

Неофициальный Android-порт веб-версии **Plants vs. Zombies 2: Gardendless**. Игра запускается локально во встроенном WebView; игровые файлы устанавливаются из ZIP-архива отдельно от APK.

## Возможности

- Лаунчер на Material Design 3 с портретной и альбомной раскладкой.
- Запуск игры в альбомной ориентации.
- Импорт сохранения через системный выбор файлов.
- Управление касаниями: одно касание работает как левая кнопка мыши, два пальца — прокрутка, одновременное касание двумя пальцами — правая кнопка.
- Поддержка GP-Next и локального хранения его данных.

## Установка игры

1. Нажмите **«Скачать ZIP из Telegram»** и скачайте игровой архив из [нашего канала](https://t.me/pvz2gardendless).
2. Вернитесь в приложение и нажмите **«Выбрать ZIP игры»**.
3. Найдите скачанный файл и дождитесь распаковки. После установки нажмите **«Запустить»**.

Архив должен содержать `index.html` и ресурсы игры. Поддерживаются, например, `index.html` в корне архива или `docs/index.html`. Установленные файлы сохраняются между запусками приложения.

Для обновления выберите **«Обновить ZIP игры»** и укажите новый архив.

## Подготовка ZIP из Docker-образа

Приложение не запускает Docker и не скачивает образ самостоятельно. Для ручного извлечения файлов на компьютере с Docker:

```powershell
docker pull ghcr.io/gzh0821/pvzge_web:latest
docker create --name pvzge-temp ghcr.io/gzh0821/pvzge_web:latest
docker cp pvzge-temp:/usr/share/nginx/html .\gardendless-game
docker rm pvzge-temp
```

Создайте ZIP из папки `gardendless-game` и импортируйте в игре.

## Сборка

Требуются Android Studio, Android SDK 36 и JDK 21. Игровой ZIP добавлять в проект или APK не нужно.

В Windows:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

## Источники и лицензия

- Исходная веб-игра: [Gzh0821/pvzge_web](https://github.com/Gzh0821/pvzge_web).
- Telegram-канал с ZIP-архивами: [pvz2gardendless](https://t.me/pvz2gardendless).
- Приложение распространяется по GNU GPL v3; текст лицензии находится в [LICENSE](LICENSE).
