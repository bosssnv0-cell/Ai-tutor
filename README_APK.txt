КАК СОБРАТЬ APK БЕЗ ANDROID STUDIO (бесплатно, ~15 минут)

СТРУКТУРА ЗАГРУЗКИ В РЕПОЗИТОРИЙ (папки создавать не нужно — GitHub сам поймёт по путям):
ai_tutor_project/
  .github/workflows/build.yml     <- скрытая папка, загрузите обязательно!
  app/build.gradle
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/example/aitutor/MainActivity.kt
  app/src/main/res/layout/activity_main.xml
  app/src/main/res/values/strings.xml
  app/src/main/assets/90_lessons_curriculum.json
  build.gradle
  settings.gradle
  README_APK.txt

1. Заведите аккаунт на github.com -> New repository (сделайте Public).

2. Загрузите все 10 файлов из этой папки, соблюдая пути выше
   (в веб-интерфейсе GitHub: Add file -> Upload files, либо через git push).

3. Вкладка Actions -> "Build APK" -> Run workflow.
   Через 3-6 минут появится зелёная галочка -> в детали запуска -> артефакт ai-tutor-apk.

4. Скачайте архив с артефактом, достаньте app-debug.apk, перебросьте на телефон
   и установите (разрешите установку из неизвестных источников).

5. Первый запуск приложения: оно попросит бесплатный read-токен Hugging Face:
   - huggingface.co -> Settings -> Access Tokens -> New token, тип READ;
   - на странице модели litert-community/Gemma3-1B-IT согласитесь с лицензией.
   Модель (~550 МБ) скачается ОДИН раз - дальше всё работает офлайн.

ТРЕБОВАНИЯ: Android 8.0+, 4 ГБ+ оперативной памяти, ~1 ГБ свободного места.
