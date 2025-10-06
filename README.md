# Texty (Aplicación móvil)

Texty es una aplicación de mensajería cifrada construida para Android con Firebase como backend. El proyecto incluye autenticación por correo, gestión de contactos mediante solicitudes de amistad, chats individuales y grupales con soporte para adjuntos cifrados, y notificaciones push con contadores locales. El objetivo de este README es documentar cómo está organizada la app, los principales flujos funcionales y las tareas necesarias para ejecutarla y mantenerla.

## Tabla de contenidos
- [Arquitectura general](#arquitectura-general)
  - [Capa de presentación](#capa-de-presentación)
  - [Capa de datos](#capa-de-datos)
  - [Capa de criptografía y utilidades](#capa-de-criptografía-y-utilidades)
  - [Notificaciones y servicios en segundo plano](#notificaciones-y-servicios-en-segundo-plano)
- [Flujos principales](#flujos-principales)
  - [Autenticación y alta de usuarios](#autenticación-y-alta-de-usuarios)
  - [Búsqueda y solicitudes de amistad](#búsqueda-y-solicitudes-de-amistad)
  - [Listado de chats](#listado-de-chats)
  - [Mensajería y adjuntos cifrados](#mensajería-y-adjuntos-cifrados)
  - [Perfil y ajustes](#perfil-y-ajustes)
  - [Notificaciones push](#notificaciones-push)
- [Modelo de datos de Firebase](#modelo-de-datos-de-firebase)
- [Configuración local](#configuración-local)
- [Ejecución y comandos útiles](#ejecución-y-comandos-útiles)
- [Funciones de Cloud y tareas de mantenimiento](#funciones-de-cloud-y-tareas-de-mantenimiento)
- [Registros y resolución de problemas](#registros-y-resolución-de-problemas)
- [Pruebas](#pruebas)
- [Contribuir](#contribuir)

## Arquitectura general

La app sigue una estructura clásica basada en capas:

### Capa de presentación
- **Actividades y fragments tradicionales:** `LoginActivity`, `RegisterActivity` y `MainActivity` gestionan la navegación principal y los permisos (notificaciones, cámara, galería). `MainActivity` aloja un `BottomNavigationView` con las secciones de chats, búsqueda, solicitudes y perfil, y gestiona la publicación del token FCM y el estado en línea en Firestore.【F:app/src/main/java/com/example/texty/ui/MainActivity.kt†L27-L135】【F:app/src/main/java/com/example/texty/ui/MainActivity.kt†L154-L205】
- **Fragments especializados:** `ChatListFragment`, `SearchUserFragment`, `FriendRequestsFragment` y `ProfileFragment` encapsulan cada sección y coordinan la UI con sus repositorios correspondientes.【F:app/src/main/java/com/example/texty/ui/SearchUserFragment.kt†L18-L97】【F:app/src/main/java/com/example/texty/ui/FriendRequestsFragment.kt†L17-L110】【F:app/src/main/java/com/example/texty/ui/ProfileFragment.kt†L16-L155】
- **Adaptadores RecyclerView:** `ChatListAdapter`, `ChatAdapter` y `UserAdapter` renderizan las listas de salas, mensajes y resultados de usuarios.

### Capa de datos
- **Repositorios Firebase:** `UserRepository`, `FriendRequestRepository`, `ChatRoomRepository`, `SessionKeyRepository` y `KeyRepository` centralizan el acceso a Firestore, Authentication y Storage. Por ejemplo, `FriendRequestRepository` gestiona solicitudes, relaciones y el intercambio transaccional de claves de sesión al aceptarlas.【F:app/src/main/java/com/example/texty/repository/FriendRequestRepository.kt†L1-L164】
- **Modelos:** `User`, `ChatRoom`, `Message`, `SessionKeyInfo`, `KeyBundle`, etc., definen los contratos con Firestore y las estructuras usadas en memoria.【F:app/src/main/java/com/example/texty/model/User.kt†L1-L63】【F:app/src/main/java/com/example/texty/model/ChatRoom.kt†L1-L16】【F:app/src/main/java/com/example/texty/model/Message.kt†L1-L34】
- **ViewModels:** `ChatListViewModel` realiza la escucha reactiva de las salas, aplica limpieza de metadatos heredados y combina datos auxiliares (ej. fotos de perfil y resúmenes cifrados).【F:app/src/main/java/com/example/texty/ui/ChatListViewModel.kt†L1-L164】

### Capa de criptografía y utilidades
- **Gestión de claves:** `KeyManager` usa `EncryptedSharedPreferences` y Tink para generar y persistir el bundle de identidad, pre-claves y claves de un solo uso, manejando casos de corrupción del Keystore.【F:app/src/main/java/com/example/texty/crypto/KeyManager.kt†L1-L134】
- **Cifrado de mensajes:** `MessageCrypto` encapsula el cifrado AEAD XChaCha20-Poly1305 con derivación HKDF, manejo de errores y reenvío de recibos de lectura.【F:app/src/main/java/com/example/texty/util/MessageCrypto.kt†L1-L122】【F:app/src/main/java/com/example/texty/util/MessageCrypto.kt†L124-L213】
- **Adjuntos cifrados:** `AttachmentCrypto` cifra/descifra archivos almacenados en Firebase Storage reutilizando la clave de sesión y mantiene un caché en memoria para descargas recientes.【F:app/src/main/java/com/example/texty/util/AttachmentCrypto.kt†L1-L118】【F:app/src/main/java/com/example/texty/util/AttachmentCrypto.kt†L120-L213】
- **Registro y telemetría:** `AppLogger` captura excepciones, conserva archivos de log rotativos y permite compartirlos; se inicializa desde `TextyApplication` para interceptar excepciones globales.【F:app/src/main/java/com/example/texty/TextyApplication.kt†L1-L20】【F:app/src/main/java/com/example/texty/util/AppLogger.kt†L1-L81】

### Notificaciones y servicios en segundo plano
- **FirebaseMessagingService:** `MessagingService` crea el canal de notificaciones, gestiona contadores por sala y guarda tokens FCM bajo el documento de usuario.【F:app/src/main/java/com/example/texty/MessagingService.kt†L1-L100】【F:app/src/main/java/com/example/texty/MessagingService.kt†L102-L154】
- **Contador local:** `NotificationCounter` persiste en `SharedPreferences` la cantidad de mensajes no leídos por sala para mostrar badges acumulativos.【F:app/src/main/java/com/example/texty/NotificationCounter.kt†L1-L34】

## Flujos principales

### Autenticación y alta de usuarios
- **Registro:** `RegisterActivity` valida los campos, crea la cuenta en Firebase Auth, genera el bundle de claves con `KeyManager` y publica el perfil en `users`, guardando la copia local en `KeyRepository` para futuros handshakes.【F:app/src/main/java/com/example/texty/ui/RegisterActivity.kt†L15-L109】
- **Inicio de sesión:** `LoginActivity` autentica al usuario, maneja errores comunes y redirige a `MainActivity` en caso de sesión activa.【F:app/src/main/java/com/example/texty/ui/LoginActivity.kt†L15-L86】

### Búsqueda y solicitudes de amistad
- La pestaña de búsqueda consulta Firestore por coincidencias de nombre mediante `UserRepository`, muestra el estado actual (ninguna relación, pendiente, amigos) y permite enviar solicitudes con `FriendRequestRepository`. Solo los contactos aceptados pueden abrir `ChatActivity`.【F:app/src/main/java/com/example/texty/ui/SearchUserFragment.kt†L30-L96】
- `FriendRequestsFragment` lista solicitudes entrantes, permitiendo aceptarlas o rechazarlas. Al aceptar, se crean/actualizan los documentos de sesión y se añaden ambos usuarios al array `friends` de cada perfil.【F:app/src/main/java/com/example/texty/ui/FriendRequestsFragment.kt†L17-L109】【F:app/src/main/java/com/example/texty/repository/FriendRequestRepository.kt†L31-L120】

### Listado de chats
- `ChatListViewModel` escucha cambios en `rooms` filtrados por `participantIds`, genera el `ChatRoom` base y suscribe listeners en `rooms/{roomId}/userState/{uid}` para obtener resúmenes cifrados, contadores de no leídos y fotos de otros usuarios. También elimina el campo legado `lastMessage` cuando aparece.【F:app/src/main/java/com/example/texty/ui/ChatListViewModel.kt†L31-L164】【F:app/src/main/java/com/example/texty/ui/ChatListViewModel.kt†L166-L244】

### Mensajería y adjuntos cifrados
- `ChatActivity` inicializa el chat con datos del intent (1:1 o grupo), verifica relaciones de amistad, escucha los mensajes ordenados y permite enviar texto o imágenes mediante `AttachmentCrypto`. Maneja banners de error/reintento cuando la clave de sesión requiere resincronización.【F:app/src/main/java/com/example/texty/ui/ChatActivity.kt†L34-L132】【F:app/src/main/java/com/example/texty/ui/ChatActivity.kt†L134-L205】
- `MessageMapper` y `SessionKeyRepository` colaboran para descifrar mensajes en segundo plano, solicitar nuevas claves y volver a cifrar el resumen por usuario cuando cambian los recibos de lectura.【F:app/src/main/java/com/example/texty/repository/MessageMapper.kt†L1-L115】【F:app/src/main/java/com/example/texty/repository/SessionKeyRepository.kt†L1-L164】

### Perfil y ajustes
- `ProfileFragment` permite actualizar nombre, biografía, teléfono y foto de perfil, subiendo la imagen a Firebase Storage y sincronizando tanto Firebase Auth como el documento en `users`. También intercepta el botón atrás cuando hay cambios sin guardar y ofrece cerrar sesión y compartir logs.【F:app/src/main/java/com/example/texty/ui/ProfileFragment.kt†L16-L222】

### Notificaciones push
- `MainActivity` solicita el permiso de notificaciones en Android 13+, obtiene el token FCM y lo agrega al array `fcmTokens` del usuario. `MessagingService` muestra notificaciones agrupadas por sala y borra los contadores al entrar en la conversación.【F:app/src/main/java/com/example/texty/ui/MainActivity.kt†L74-L135】【F:app/src/main/java/com/example/texty/MessagingService.kt†L25-L100】

## Modelo de datos de Firebase

| Colección | Uso principal | Campos destacados |
|-----------|---------------|-------------------|
| `users` | Perfiles públicos y datos de clave | `displayName`, `photoUrl`, `friends[]`, `identityPublicKey`, `signedPreKey`, `oneTimePreKeys[]`, `fcmTokens[]`, `isOnline`.【F:app/src/main/java/com/example/texty/ui/RegisterActivity.kt†L71-L102】【F:app/src/main/java/com/example/texty/repository/FriendRequestRepository.kt†L106-L164】 |
| `friend_requests` | Solicitudes pendientes | `fromUid`, `toUid`, `status` (`pending`, `accepted`).【F:app/src/main/java/com/example/texty/repository/FriendRequestRepository.kt†L21-L64】 |
| `sessions` | Claves de sesión negociadas entre pares | Documentos creados/actualizados al aceptar solicitudes o refrescar sesiones, incluyendo material cifrado para cada participante.【F:app/src/main/java/com/example/texty/repository/FriendRequestRepository.kt†L65-L164】 |
| `rooms` | Metadatos de conversaciones | `participantIds[]`, `userNames{}`, `isGroup`, `groupName`, `updatedAt`, `unreadCounts{}`, `groupPhotoUrl`. Resúmenes cifrados por usuario en `rooms/{roomId}/userState/{uid}`.【F:app/src/main/java/com/example/texty/ui/ChatListViewModel.kt†L43-L164】 |
| `rooms/{roomId}/messages` | Mensajes cifrados | `encryption` (`ciphertext`, `nonce`, `salt`, `schemeVersion`, `encryptionTarget`), `senderId`, `readBy[]`, metadatos de adjuntos cuando aplica.【F:app/src/main/java/com/example/texty/model/Message.kt†L1-L34】 |
| Firebase Storage | Imágenes de perfil y adjuntos de chat | Las rutas se almacenan en `MessageBody.attachmentStoragePath` o `profileImages/{uid}.jpg`. Los adjuntos se cifran con derivación HKDF antes de subirlos.【F:app/src/main/java/com/example/texty/util/AttachmentCrypto.kt†L36-L118】【F:app/src/main/java/com/example/texty/ui/ProfileFragment.kt†L108-L187】 |

## Configuración local

1. **Android SDK:** crea un archivo `local.properties` en la raíz con la ruta a tu SDK (`sdk.dir=/ruta/al/Android/Sdk`). No lo subas al control de versiones.【F:README.md†L1-L4】
2. **Firebase:**
   - Coloca `google-services.json` dentro de `app/` (ya incluido si se suministra el archivo).【F:app/google-services.json†L1-L2】
   - Configura Firebase Authentication (correo/contraseña), Cloud Firestore, Cloud Storage y Cloud Messaging.
   - Sube las reglas necesarias para proteger las colecciones según tus requisitos de seguridad.
3. **Funciones de Cloud:** instala dependencias en `functions/` (`npm install`) y usa Node 18 como indica `package.json`.【F:functions/package.json†L1-L7】
4. **Variables y claves locales:** la app utiliza `EncryptedSharedPreferences` para almacenar material criptográfico, por lo que no se requiere configuración adicional.

## Ejecución y comandos útiles

- Compilar en modo debug: `./gradlew assembleDebug`
- Ejecutar en un dispositivo/emulador desde Android Studio (o `./gradlew installDebug`)
- Pruebas unitarias locales: `./gradlew testDebugUnitTest`
- Pruebas instrumentadas: `./gradlew connectedDebugAndroidTest`

## Funciones de Cloud y tareas de mantenimiento

- **Despliegue:** `firebase deploy --only functions`
- **Limpieza de metadatos heredados:** el script `functions/scripts/purgeLastMessage.js` elimina el campo obsoleto `lastMessage` de todas las salas y puede ejecutarse manualmente o programarse como tarea recurrente hasta que los clientes legacy se actualicen.【F:functions/scripts/purgeLastMessage.js†L1-L36】
- **Rotación de claves:** `KeyRepository` y `KeyManager` exponen helpers para generar nuevas pre-claves cuando el pool local baja del umbral configurado, y `MainActivity` fuerza la refrescada al iniciar sesión.【F:app/src/main/java/com/example/texty/ui/MainActivity.kt†L137-L205】【F:app/src/main/java/com/example/texty/crypto/KeyManager.kt†L118-L195】

## Registros y resolución de problemas

- `AppLogger` guarda logs rotativos (máx. 20 archivos y 20 MB) en `files/logs/` y permite compartirlos mediante un `FileProvider`. También intercepta excepciones no capturadas desde `TextyApplication` para facilitar el soporte remoto.【F:app/src/main/java/com/example/texty/util/AppLogger.kt†L13-L77】【F:app/src/main/java/com/example/texty/TextyApplication.kt†L8-L20】
- Usa `AppLogger.logError` en flujos críticos (ya integrado en actividades/fragments) para registrar fallas y ayudar a reproducirlas.

## Pruebas

- **Unitarias:** existen dependencias para JUnit, MockK y Robolectric. Ejecuta `./gradlew testDebugUnitTest` para validar lógica sin dispositivo físico.【F:app/build.gradle.kts†L58-L74】
- **Instrumentadas:** Espresso y AndroidX Test están disponibles para pruebas de UI (`./gradlew connectedDebugAndroidTest`).【F:app/build.gradle.kts†L71-L74】
- **Manual:** consulta `docs/manual-tests.md` para flujos específicos como la confirmación de cierre de sesión.【F:docs/manual-tests.md†L1-L8】

## Contribuir

1. Crea una rama desde `main`.
2. Sigue las guías de estilo de Kotlin de Android y usa los repositorios existentes para acceder a Firebase.
3. Añade pruebas cuando modifiques lógica crítica (cifrado, repositorios, autenticación).
4. Asegúrate de ejecutar el script de limpieza si introduces cambios que afecten metadatos heredados.
5. Abre un Pull Request describiendo claramente el cambio, las pruebas ejecutadas y cualquier migración necesaria.
