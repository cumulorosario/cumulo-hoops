# 🏀 Cúmulo Hoops

Tablero de básquet para clubes. Un **celular Android** hace de servidor: genera el hotspot, las TVs muestran el tablero y el reloj de 24 en su navegador, y el operador carga el partido desde ese mismo celular, desde otro o desde una PC. No necesita internet.

| Pantalla | Dirección | Dónde |
|---|---|---|
| Conectar (QR) | `http://IP:3000` | Se abre sola en la app |
| Mesa de control | `http://IP:3000/operador` | Celular, tablet o PC (uno o varios) |
| Tablero principal | `http://IP:3000/tablero` | TV grande |
| Reloj de 24 | `http://IP:3000/posesion` | TVs chicas (`?solo` = solo el número, `?mudo` = sin chicharra) |

## Instalar la app

1. En el celular, abrí **Releases** de este repositorio (columna derecha en GitHub) y descargá el último `cumulo-hoops-*.apk`.
2. Abrilo y aceptá *Instalar apps desconocidas* para el navegador.
3. Las versiones nuevas se instalan encima de la anterior sin perder el partido guardado.

## Usar en un partido

1. Abrí **Cúmulo Hoops**. Arranca el servidor y queda una notificación fija mientras está activo.
2. Tocá **Hotspot** y activá la zona Wi-Fi del celular (o conectá el celular al WiFi del club).
3. Conectá las TVs a esa red y escaneá los QR o escribí la dirección. En cada TV presioná OK una vez para activar la chicharra y la pantalla completa.
4. Para operar desde el mismo celular tocá **Abrir aquí** en *Mesa de control*. Desde otros dispositivos, escaneá el QR del operador.
5. La primera vez tocá **Batería** y permití que la app funcione sin restricciones, para que Android no la cierre.

El servidor sigue funcionando con la pantalla apagada o si cambiás de app. Se detiene con **Detener** o desde la notificación.

## Cómo está hecho

```
app/src/main/java/ar/cumulo/hoops/
  core/Game.kt        reglas y estado del partido (fuente de verdad)
  core/WebServer.kt   servidor HTTP + eventos en vivo (Server-Sent Events), sin librerías
  core/Json.kt        JSON mínimo
  ServerService.kt    servicio en primer plano que mantiene vivo el servidor
  MainActivity.kt     pantalla principal (muestra la página Conectar)
web/                  pantallas: operador, tablero, reloj de 24 (compartidas con la versión PC)
pc/                   servidor alternativo en Node.js para probar desde una PC
```

- El núcleo (`core/`) es Kotlin puro: no depende de Android, se prueba en cualquier JVM (`app/src/test`).
- Las pantallas calculan el reloj localmente, sincronizado con la hora del servidor, así que el tiempo corre fluido aunque la red tenga microcortes.
- El partido se guarda en el celular cada pocos segundos y se recupera si la app se cierra.

## Compilar

Cada `push` a `main` corre los tests, compila el APK y lo publica en **Releases** (GitHub Actions, `.github/workflows/apk.yml`).

En una computadora: abrir la carpeta con **Android Studio**, o `./gradlew assembleRelease` con el SDK de Android instalado.

La firma usa `app/debug.keystore` (clave de pruebas incluida a propósito para que las actualizaciones se instalen encima). Antes de publicar en Play Store hay que generar una clave propia y guardarla como secreto del repositorio.

## Versión PC (opcional)

Con Node.js instalado: `cd pc && node server.js` (o doble clic en `pc/iniciar.bat` en Windows). Usa las mismas pantallas. Si cambia una regla del partido, actualizar `core/Game.kt` y `pc/server.js`.
