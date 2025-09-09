# movil

## Configuración local

El archivo `local.properties` contiene la ruta al SDK de Android y no debe añadirse al control de versiones.
Para generarlo de forma local puedes abrir el proyecto en Android Studio o ejecutar `sdkmanager` desde la línea de comandos.
En ambos casos se creará un archivo con el contenido similar a:

```
sdk.dir=/ruta/al/Android/Sdk
```

Asegúrate de crear este archivo antes de compilar el proyecto.
