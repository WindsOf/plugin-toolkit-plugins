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
  - Introdotto supporto nativo per le immagini in formato `.webp` tramite l'integrazione di `imageio-webp` (TwelveMonkeys), consentendo al plugin di elaborare direttamente i file WebP senza doverli pre-convertire o causare eccezioni `NoClassDefFoundError`.
Fixed:
  - Risolti blocchi infiniti (hang/freeze per svariati minuti) durante l'elaborazione di formati complessi come il WebP. Modificato il parser I/O per leggere direttamente da `java.io.File` anziché da uno Stream in memoria, garantendo stabilità nativa e supporto all'accesso random-access.
Changed:
  - Enorme ottimizzazione delle prestazioni nell'analisi dei pixel (`analyzeSingleRowVariance`): rimosso il fetch "pixel per pixel", sostituendolo con un'estrazione massiva di intere righe tramite buffer di memoria. Abbattuti drasticamente i tempi di esecuzione sui panel webtoon ad alta risoluzione.
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
version: 1.0.1
date: 2026-05-10
Changes:
    - Recompiled for 1.3.1
    - Switched from including the plugin-api as a module to including it as a dependency
----------------------------------------------------------------------------------------------------
version: 1.0.0
date: 2026-05-04
Added:
    - Slicer
    - Valid cut distance checker
Planned:
    - Dynamic image loading in teh slicer to avoid loading all the images bitmap in ram