<div align="center">

<img src="TMessagesProj/src/main/res/mipmap-xxxhdpi/ic_launcher_duckduckgram.png" width="128" alt="DuckDuckGram">

# DuckDuckGram

Один Telegram-клиент, в котором собраны возможности разных открытых клиентов

</div>

---

## Что это

DuckDuckGram это Telegram-клиент, состоящий из возможностей других открытых клиентов.
Из exteraless, NagramXF и остальных взято лучшее и собрано в один APK.

Проект придерживается принципа: чем больше возможностей, тем лучше. Переходя на
DuckDuckGram, пользователь сохраняет любимые функции из других клиентов, а не выбирает
между ними.

Из возможностей:

- Полный арсенал exteraless: оформление и темы, иконпаки, Pill Stack, боковое меню,
  настройки чатов, AI-функции и другое.
- Безопасность плагинов из exteraless: плагины не имеют безграничного доступа.
  У каждого из них модель разрешений, плагин объявляет, что ему нужно, это видно на
  экране установки, и получает он только заявленное.
- Java-плагины со своим SDK (подробнее в
  [docs/JAVA_PLUGINS_SDK.md](docs/JAVA_PLUGINS_SDK.md)): HTTP-запросы, синхронные и
  асинхронные, собственные экраны настроек, изменение оформления и поведения.
- Python-плагины (движок exteraless) для гибкости без переписывания на Java.
- Бейджи поддержавших: у тех, кто поддержал проект, рядом с ником появляется фирменный
  значок, в списке чатов, в профиле и в истории сообщений. Значок покупается за
  Telegram Stars через [@DDG_Badgesbot](https://t.me/DDG_Badgesbot).
- Приватность и ghost-режим, экспорт и импорт настроек, proxy, эквалайзер, обновления
  из приложения и остальное, что накопилось за годы развития форков.

* Имя пакета: `com.duckduckgram.duckerer`

### Ссылки

* Исходники: [github.com/Duckerer/DuckDuckGram](https://github.com/Duckerer/DuckDuckGram)
* Дока SDK плагинов: `docs/JAVA_PLUGINS_SDK.md`

### Сборка

1. Склонировать репозиторий вместе с подмодулями:

    ```bash
    git clone --recursive --shallow-submodules https://github.com/Duckerer/DuckDuckGram.git duckduckgram
    ```

    Если репозиторий уже склонирован без подмодулей:

    ```bash
    git submodule update --init --recursive --depth=1
    ```

2. Получить `TELEGRAM_APP_ID` и `TELEGRAM_APP_HASH` на [my.telegram.org](https://my.telegram.org/auth)
   и создать `local.properties` в корне проекта:

   ```properties
   TELEGRAM_APP_ID=<ваш_app_id>
   TELEGRAM_APP_HASH=<ваш_app_hash>
   ```

3. Опционально добавить свои ключи карт и Nothing Glyph:

   ```properties
   MAPS_API_KEY=<ваш_key>
   GLYPH_API_KEY=<ваш_key>
   ```

4. Для подписи APK положить свой `TMessagesProj/release.keystore` и дописать в `local.properties`:

   ```properties
   KEYSTORE_PASS=<пароль_хранилища>
   ALIAS_NAME=<имя_ключа>
   ALIAS_PASS=<пароль_ключа>
   ```

   Ключа в репозитории нет намеренно. Без него сборка не падает, APK подписывается
   отладочным ключом Android.

5. Для push-уведомлений положить свой `TMessagesProj/google-services.json`
   (Firebase, имя пакета `com.duckduckgram.duckerer`).

6. Заменить метаданные проекта под себя:

    - `BaseRemoteHelper.CHANNEL_METADATA_ID`, числовой id вашего канала метаданных,
      без префикса `-100`.

7. Собрать: `./gradlew :TMessagesProj:assembleDebug` или открыть проект в Android Studio.

**Про ABI.** Собираются только 64-битные `arm64-v8a` и `x86_64`: Chaquopy собирает
Python 3.12 лишь под них, и на `armeabi-v7a` конфигурация обрывается. Переменная
`NATIVE_TARGET` задаёт цель: `arm64-v8a` (один ABI, быстрее), `universal` (оба),
`SKIP` (без нативной части, только Java и ресурсы).

### Сборка через GitHub Actions

Нужны два секрета репозитория:

* `LOCAL_PROPERTIES`, содержимое `local.properties` в base64:

  ```bash
  base64 -w0 local.properties
  ```

* `RELEASE_KEYSTORE`, файл ключа в base64:

  ```bash
  base64 -w0 TMessagesProj/release.keystore
  ```

Дальше запустить workflow **Release Build**. Готовый APK лежит в артефактах прогона.

### Приватность и безопасность

DuckDuckGram не имеет своих серверов и не собирает телеметрию. Сеть используется
только по вашему действию: сам Telegram, а также встроенные сервисы (AI, перевод,
транскрипция, Last.fm, карты, проверка обновлений). Ключи этих сервисов хранятся
локально в настройках приложения.

- Ключи сторонних сервисов лежат в обычных локальных настройках и могут попасть в
  файл при экспорте настроек. Не делитесь таким файлом.
- У плагинов есть модель разрешений, и они получают только заявленное, но плагин это
  сторонний код. Ставьте только те, которым доверяете.
- Ключ подписи, ключи Telegram API и ключи сервисов в репозиторий не кладутся.

Проект распространяется под GPLv3 (`LICENSE`). Он содержит компоненты с собственными
лицензиями (ffmpeg, boringssl, webrtc, breakpad и другие), условия которых описаны в
соответствующих подкаталогах исходников. Telegram и логотип Telegram принадлежат
Telegram FZ-LLC, проект с Telegram не связан.

### Поддержать проект

Понравился клиент? Поддержи разработку, и бейдж поддержавшего появится у тебя рядом
с ником во всех чатах: [@DDG_Badgesbot](https://t.me/DDG_Badgesbot), оплата за
Telegram Stars.

### Авторы дизайна

Иконки и оформление, унаследованные от exteraGram, созданы его дизайнером,
[@the8055u](https://t.me/the8055u), и студией [@BlueprintDsgn](https://t.me/BlueprintDsgn).
Права на эти материалы принадлежат авторам.

### Благодарности

- [exteraGram](https://github.com/exteraSquad/exteraGram), база возможностей и дизайна
- [exteraless](https://github.com/exteraless/exteraless), открытая реализация exteraGram, модель разрешений плагинов
- [Nagram](https://github.com/NextAlone/Nagram) и [NagramX](https://github.com/risin42/NagramX), основа проекта
- [NagramXF](https://github.com/usv/NagramXF-Extera)
- [Plus Messenger](https://github.com/rafalense/Plus-Messenger)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [Dr4iv3rNope](https://github.com/Dr4iv3rNope/NotSoAndroidAyuGram)
- [Inugram](https://github.com/teidesu/inugram)
- [Nekogram](https://github.com/Nekogram/Nekogram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)

---

## English

### What this is

DuckDuckGram is a Telegram client made of the best capabilities of other open-source
clients. The useful pieces of exteraless, NagramXF and others are combined into a
single APK.

The project follows one principle: the more features, the better. Someone switching to
DuckDuckGram keeps the features they like from other clients instead of choosing
between them.

Highlights:

- The full exteraless arsenal: theming, icon packs, Pill Stack, drawer, chat settings,
  AI features and more.
- exteraless plugin security: plugins are not all-powerful. Each plugin has a permission
  model, declares what it needs, that is visible on the install sheet, and it gets only
  what was declared.
- Java plugins with their own SDK (see
  [docs/JAVA_PLUGINS_SDK.md](docs/JAVA_PLUGINS_SDK.md)): synchronous and asynchronous
  HTTP calls, custom settings screens, theming and behavior changes.
- Python plugins (the exteraless engine) for flexibility without rewriting in Java.
- Supporter badges: people who support the project get a custom badge next to their
  name, in the chat list, profile and message history. Badges are bought for
  Telegram Stars via [@DDG_Badgesbot](https://t.me/DDG_Badgesbot).
- Privacy and ghost mode, settings export and import, proxy, equalizer, in-app updates
  and everything else accumulated over years of fork development.

* Package name: `com.duckduckgram.duckerer`

### Support the project

Like the client? Support development and get the supporter badge next to your name in
every chat: [@DDG_Badgesbot](https://t.me/DDG_Badgesbot), paid with Telegram Stars.

### Design credits

Icons and visual design inherited from exteraGram are the work of its designer,
[@the8055u](https://t.me/the8055u), and the [@BlueprintDsgn](https://t.me/BlueprintDsgn)
studio. Rights to those materials belong to their authors.

### Acknowledgements

- [exteraGram](https://github.com/exteraSquad/exteraGram), the feature and design base
- [exteraless](https://github.com/exteraless/exteraless), open reimplementation of exteraGram, plugin permission model
- [Nagram](https://github.com/NextAlone/Nagram) and [NagramX](https://github.com/risin42/NagramX), the foundation of the project
- [NagramXF](https://github.com/usv/NagramXF-Extera)
- [Plus Messenger](https://github.com/rafalense/Plus-Messenger)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [Dr4iv3rNope](https://github.com/Dr4iv3rNope/NotSoAndroidAyuGram)
- [Inugram](https://github.com/teidesu/inugram)
- [Nekogram](https://github.com/Nekogram/Nekogram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)

### Building

1. Clone with submodules:

    ```bash
    git clone --recursive --shallow-submodules https://github.com/Duckerer/DuckDuckGram.git duckduckgram
    ```

2. Get `TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH` from [my.telegram.org](https://my.telegram.org/auth)
   and put them into `local.properties` in the project root.

3. Optionally add your own `MAPS_API_KEY` and `GLYPH_API_KEY`.

4. For release signing, drop your own `TMessagesProj/release.keystore` and add
   `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS` to `local.properties`. No keystore is
   shipped with the repository; without one the build is signed with the Android debug key.

5. For push notifications, replace `TMessagesProj/google-services.json` with your own
   Firebase config for `com.duckduckgram.duckerer`.

6. Build with `./gradlew :TMessagesProj:assembleDebug` or from Android Studio.

Only 64-bit ABIs are built (`arm64-v8a`, `x86_64`): Chaquopy ships Python 3.12 for those
only, and `armeabi-v7a` fails at configuration time. `NATIVE_TARGET` selects the target:
`arm64-v8a`, `universal` (both) or `SKIP` (no native part).

For CI, set two repository secrets, `LOCAL_PROPERTIES` and `RELEASE_KEYSTORE`, both
base64-encoded, and run the **Release Build** workflow. The APK ends up in the run
artifacts.

### Privacy and security

DuckDuckGram has no servers of its own and collects no telemetry. The network is used
only on your action: Telegram itself and the built-in services (AI, translation,
transcription, Last.fm, maps, update check). The keys for those services are stored
locally in the app settings.

- Third-party service keys live in plain local settings and can end up in a settings
  export file. Do not share that file.
- Plugins have a permission model and get only what they declared, but a plugin is
  third-party code. Install only ones you trust.
- The signing key, Telegram API keys and service keys are not committed to the
  repository.

The project is released under GPLv3 (`LICENSE`). It bundles components with their own
licenses (ffmpeg, boringssl, webrtc, breakpad and others); their terms are described in
the corresponding source subdirectories. Telegram and the Telegram logo belong to
Telegram FZ-LLC; this project is not affiliated with Telegram.