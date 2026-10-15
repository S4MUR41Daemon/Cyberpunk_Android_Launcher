# ATMOS//OS Launcher

## ⬇️ Descargar

**[Descarga la última APK aquí](https://github.com/S4MUR41Daemon/Cyberpunk_Android_Launcher/releases/latest)** (en *Assets*). No hace falta compilar nada: ábrela en el móvil y acepta instalar.
Las versiones se publican solas: al crear un tag `vX.Y.Z`, GitHub Actions compila la APK firmada y la sube a *Releases*.


Launcher Android (Kotlin + Jetpack Compose) con la estética de la app del tiempo **ATMOS**:
paneles con esquinas cortadas, cabeceras numeradas `01 //`, números rayados con aberración cromática
y las mismas 4 plantillas (Night City, EVA001, Noche de neón, Green Lantern).

Referencia de diseño: `../ATMOS OS Launcher.dc.html` e `../Instrucciones Claude Code.md`.

## Pantallas

- **Inicio**: hora rayada, módulo ATMOS, mini-player, cuadrícula 3×3 editable (pulsación larga) o variante lista.
- **Cajón**: buscador `> BUSCAR_`, índice alfabético, pulsación larga → info / desinstalar / favoritos.
- **NEURO//AUDIO** y **reproductor**: sesión multimedia activa (Spotify, YT Music…), registro de escucha, visualizador.
- **Mensajes / chat**: conversaciones reconstruidas desde las notificaciones de WhatsApp (u otras), con respuesta.
- **Tiempo**: resumen de ATMOS; al tocarlo abre ATMOS.
- **Panel**: desliza hacia abajo en el inicio o toca la barra superior. Accesos rápidos, brillo, tema y notificaciones.
- **Ajustes**: pulsación larga en la barra superior del inicio, o «AJUSTES ›» en el panel.

## Integración con ATMOS

ATMOS expone un `ContentProvider` (`com.atmos.weather.data`) protegido por un permiso **de firma**.
El launcher lee de ahí el tiempo ya cacheado (no hace peticiones de red propias) y sincroniza el tema
en ambos sentidos. **Las dos apps deben estar firmadas con la misma clave**: compílalas en el mismo PC
(usan su `debug.keystore`). Si una viene de GitHub Actions, el launcher mostrará «FIRMADA CON OTRA CLAVE».

## Compilar

```bash
./gradlew testDebugUnitTest assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk` (R8, ~2 MB).

## Rendimiento y batería

Sin servicios en primer plano, wakelocks ni alarmas. Reloj por `ACTION_TIME_TICK` solo en primer plano;
lista de apps por `LauncherApps.Callback`; listener de notificaciones pasivo; scanlines como un único
shader estático; transiciones de ≤250 ms solo con `graphicsLayer`; visualizador animado solo mientras suena.
