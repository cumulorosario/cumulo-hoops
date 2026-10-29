# 🏀 Cúmulo Hoops

Tablero de básquet para clubes, desarrollado por **Cúmulo Ingeniería**. Un **celular Android** hace de servidor: genera el hotspot, las TVs muestran el tablero y el reloj de 24 en su navegador, y el operador carga el partido desde ese mismo celular, desde otro o desde una PC. No necesita internet.

| Pantalla | Dirección | Contraseña | Dónde |
|---|---|---|---|
| Conectar (QR y contraseñas) | `http://IP:3000` | — | Se abre sola en la app |
| Mesa de control | `http://IP:3000/operador` | operador | Celular, tablet o PC (uno o varios) |
| Nómina | `http://IP:3000/nomina` | operador | Planteles, clubes y fotos |
| Estadísticas | `http://IP:3000/estadisticas` | operador | Planilla y compartir |
| Tablero principal | `http://IP:3000/tablero` | tablero | TV grande |
| Reloj de 24 | `http://IP:3000/posesion` | reloj de 24 | TVs chicas (`?solo` = solo el número, `?mudo` = sin chicharra) |

## Instalar la app

1. En el celular, abrí **Releases** de este repositorio (columna derecha en GitHub) y descargá el último `cumulo-hoops-*.apk`.
2. Abrilo y aceptá *Instalar apps desconocidas* para el navegador.
3. Las versiones nuevas se instalan encima de la anterior sin perder el partido, las contraseñas ni las fotos.

## Usar en un partido

1. Abrí **Cúmulo Hoops**. Arranca el servidor y queda una notificación fija mientras está activo.
2. Tocá **Hotspot** y activá la zona Wi-Fi del celular (o conectá el celular al WiFi del club).
3. La primera vez, en la pantalla **Conectar**, bajá hasta **Contraseñas de acceso** y definí al menos la de la mesa de control.
4. Conectá las TVs a esa red y escaneá los QR o escribí la dirección. Cada TV pide su contraseña una sola vez (si la tiene) y después presionás OK para activar la chicharra y la pantalla completa.
5. Para operar desde el mismo celular tocá **Abrir aquí** en *Mesa de control*. Desde otros dispositivos, escaneá el QR del operador.
6. La primera vez tocá **Batería** y permití que la app funcione sin restricciones, para que Android no la cierre.

## Contraseñas

- Cada pantalla (operador, tablero, reloj de 24) puede tener su contraseña. Sin contraseña, queda abierta para cualquiera conectado a la red.
- Se cambian **solo desde el celular servidor** (pantalla Conectar). Ese celular siempre tiene acceso.
- Quien entra como operador también puede abrir el tablero y el reloj de 24.
- Cada dispositivo la ingresa una vez y queda habilitado, incluso si se reinicia el servidor. Si se cambia una contraseña, los dispositivos que usaban la anterior la tienen que volver a ingresar.
- Se guardan cifradas (hash con sal). Después de 8 intentos fallidos, el dispositivo queda bloqueado 5 minutos.

## Mesa de control

- Cada equipo muestra solo los **5 jugadores en cancha**. Tocá uno para **seleccionarlo**: los botones **+1, +2, +3 y Falta** se le imputan a él hasta que elijas otro (o ✕ para anotar al equipo sin jugador).
- **⇄ Cambio**: sale el jugador seleccionado y se abre una ventana para elegir al reemplazante entre los suplentes. Se deshace con un solo *Deshacer*. Si faltan jugadores en cancha aparece *+ Ingresar jugador*.
- La selección es propia de cada dispositivo: si hay dos operadores, cada uno tiene la suya.

## Nómina y formaciones

**📋 Nómina** (desde la mesa de control) es donde se cargan los planteles completos y los clubes:

- escudo (foto con la cámara o de la galería), nombre, sigla y color de cada club;
- jugadores: número, nombre, foto y si arranca en cancha (●); agregar, editar o quitar (hasta 20 por equipo);
- también se puede pegar el plantel entero, un jugador por línea.

Las fotos se recortan en el mismo dispositivo antes de enviarse y quedan guardadas **solo en el celular servidor**; no se suben a internet.

## Estadísticas

📊 **Estadísticas** en la mesa de control muestra:

- marcador y parciales por cuarto;
- la planilla de cada jugador: puntos, libres, dobles, triples y faltas.

Para compartir:

- **Imagen:** una imagen con el resultado y la planilla, con la marca de Cúmulo Ingeniería, lista para WhatsApp o redes.
- **Texto:** un resumen para WhatsApp.
- **Planilla CSV:** se abre con Excel o Google Sheets.

## Cómo está hecho

```
app/src/main/java/ar/cumulo/hoops/
  core/Game.kt        reglas y estado del partido de básquet (fuente de verdad)
  core/Security.kt    contraseñas por pantalla y sesiones de los dispositivos
  core/MediaStore.kt  fotos de escudos y jugadores
  core/WebServer.kt   servidor HTTP + eventos en vivo (Server-Sent Events), sin librerías
  core/Json.kt        JSON mínimo
  ServerService.kt    servicio en primer plano que mantiene vivo el servidor
  MainActivity.kt     pantalla principal, cámara/galería y compartir
  FilesProvider.kt    archivos temporales para la cámara y para compartir
web/                  pantallas: conectar, ingreso, operador, tablero, reloj de 24, estadísticas
pc/                   versión para PC del mismo servidor (.jar)
```

- El núcleo (`core/`) es Kotlin puro: no depende de Android, se prueba en cualquier JVM (`app/src/test`) y es el mismo en el celular y en la PC.
- Las pantallas calculan el reloj localmente, sincronizado con la hora del servidor, así que el tiempo corre fluido aunque la red tenga microcortes.
- El partido se guarda en el celular cada pocos segundos y se recupera si la app se cierra.
- Las pantallas usan JavaScript compatible con navegadores de Smart TV.

## Compilar

Cada `push` a `main` corre los tests, compila el APK y la versión de PC, y los publica en **Releases** (GitHub Actions, `.github/workflows/apk.yml`).

En una computadora: abrir la carpeta con **Android Studio**, o `./gradlew assembleRelease` (APK) y `./gradlew :pc:jar` (PC) con el SDK de Android instalado.

La firma usa `app/debug.keystore` (clave de pruebas incluida a propósito para que las actualizaciones se instalen encima). Antes de publicar en Play Store hay que generar una clave propia y guardarla como secreto del repositorio.

## Versión PC (opcional)

Para usar una notebook como servidor: descargar `cumulo-hoops-pc-*.zip` de **Releases**, descomprimir y abrir `iniciar.bat` (requiere **Java 17** o superior, por ejemplo [Temurin](https://adoptium.net)). En Mac o Linux: `java -jar cumulo-hoops-pc.jar --abrir`. El partido, las contraseñas y las fotos se guardan en la carpeta `datos` junto al programa.
