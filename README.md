# Multi-AI Search para Android (tablet)

Una pregunta, seis IAs, cada una en su WebView. En tablet se ven 2 columnas.

## Obtener el APK con GitHub (sin Android Studio)
1. Crea un repositorio nuevo en github.com (puede ser privado).
2. Descomprime este zip. En el repositorio: "Add file" > "Upload files" y arrastra TODO el contenido
   de la carpeta (incluida `.github`). Pulsa "Commit changes".
   - Si `.github/workflows/build.yml` no aparece en el repositorio: "Add file" > "Create new file",
     escribe `.github/workflows/build.yml` como nombre y pega el contenido del archivo.
3. Abre la pestaña **Actions**. Verás "Build APK" ejecutándose (tarda 3-6 minutos).
4. Al terminar, entra en esa ejecución, baja hasta **Artifacts** y descarga `MultiAISearch-apk`.
5. Descomprime ese zip: dentro está `app-debug.apk`. Pásalo a la tablet e instálalo
   (permite "instalar apps desconocidas" para el explorador de archivos).

Si la compilación falla, copia el error de la pestaña Actions (paso "Compilar APK de depuración").

## Uso
- Escribe la pregunta y pulsa Buscar. Chips de colores = activar/desactivar cada IA.
- ⛶ amplía un panel (la tecla Atrás vuelve). ↻ recarga ese panel.
- Seleccionando texto en otra app: menú de texto o Compartir > Multi-AI Search.
