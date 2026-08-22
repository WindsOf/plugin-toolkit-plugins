Version: 1.2.0
Date: 2026-08-22
Added:
  - Upgraded to plugin-api 2.0.0.
  - Declared supported operating systems: WINDOWS, LINUX, MACOS.
  - Implemented @PluginLoad, @PluginSetup, @PluginValidate, and @PluginUpdate lifecycle hooks.
  - Added unit test suite covering slicing execution and lifecycle hooks.
-------------------------------------------------------------------------------------------------
Version: 1.1.7
Date: 2026-07-13
Added:
  - Support for 1.7.0
-------------------------------------------------------------------------------------------------
Version: 1.1.6
Date: 2026-06-29
Added:
  - Support for 1.7.0
Bugfix:
  - Working with webp images would take ages
-------------------------------------------------------------------------------------------------
Version: 1.1.4
Date: 2026-06-01
Added:
  - Introdotto supporto nativo per le immagini in formato `.webp` tramite l'integrazione di `imageio-webp` (TwelveMonkeys).
Fixed:
  - Risolti blocchi durante l'elaborazione di formati WebP con lettura diretta da file.
Changed:
  - Ottimizzazione delle prestazioni nell'analisi dei pixel tramite estrazione massiva a buffer.
-------------------------------------------------------------------------------------------------
Version: 1.1.0
Date: 2026-05-23
Added:
  - Support for toolkit 1.5.1
Changes:
  - Changed plugin id from com.wip.operations.slicer to com.wip.slicer
-------------------------------------------------------------------------------------------------
Version: 1.0.3
Date: 2026-05-20
Added:
    - Support for toolkit 1.5.0
-------------------------------------------------------------------------------------------------
Version: 1.0.2
Date: 2026-05-12
Added:
    - Support for toolkit 1.4.0
-------------------------------------------------------------------------------------------------
Version: 1.0.1
Date: 2026-05-10
Changes:
    - Recompiled for 1.3.1
    - Switched from including the plugin-api as a module to including it as a dependency
-------------------------------------------------------------------------------------------------
Version: 1.0.0
Date: 2026-05-04
Added:
    - Initial release of Slicer
