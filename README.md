# movil

## Configuración local

El archivo `local.properties` contiene la ruta al SDK de Android y no debe añadirse al control de versiones.
Para generarlo de forma local puedes abrir el proyecto en Android Studio o ejecutar `sdkmanager` desde la línea de comandos.
En ambos casos se creará un archivo con el contenido similar a:

```
sdk.dir=/ruta/al/Android/Sdk
```

Asegúrate de crear este archivo antes de compilar el proyecto.

## Previsualizaciones cifradas y limpieza de metadatos

Los resúmenes que aparecen en la lista de chats se guardan ahora por-usuario en
`rooms/{roomId}/userState/{uid}` y se cifran con la misma clave de sesión que los
mensajes. El campo heredado `rooms/{roomId}.lastMessage` ya no se utiliza y el
cliente lo elimina de forma proactiva cada vez que se abre un chat o cuando se
sincroniza la lista de salas.

### Migración / rotación

1. Despliega las nuevas Cloud Functions y actualiza los clientes móviles.
2. Ejecuta una limpieza inicial para eliminar cualquier `lastMessage`
   persistente:

   ```bash
   cd functions
   node scripts/purgeLastMessage.js
   ```

3. Mantén la política de rotación periódica repitiendo el script anterior o
   programando una tarea automática hasta que todos los clientes legados se
   hayan actualizado. El código móvil seguirá enviando el campo como
   `FieldValue.delete()` para garantizar que no reaparezca.
