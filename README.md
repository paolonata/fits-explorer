# FITS Explorer

Visualizzatore Android nativo Kotlin/Jetpack Compose per FITS ASIAIR, completamente offline. Android 10–16 (API 29–36). Nessuna WebView, pubblicità, telemetria o autorizzazione Internet/archiviazione.

## Uso

Apri FITS → elaborazione automatica → regola Nero/Bianco/Gamma/Stretch → Salva JPG. Il JPG viene pubblicato in **Pictures/FITS Explorer**, alla risoluzione originale. “Apri immagine” apre il risultato con la Galleria installata. Auto ripristina 0%/100%, gamma 1, stretch asinh 5. Zoom con due dita, pan, +/− e Adatta. I file sorgente sono aperti esclusivamente in lettura.

Il selettore SAF mostra tutti i file: diversi provider Android identificano `.fit`, `.fits`, `.fts` come dati generici. La validazione avviene sul contenuto FITS.

## Formati e algoritmi

- FITS primari 2D, BITPIX 8/16/32/-32/-64, big endian, BSCALE, BZERO, BLANK e pixel non finiti.
- Bayer RGGB, BGGR, GRBG, GBRG da BAYERPAT; XBAYROFF/YBAYROFF rispettati. Assenza di BAYERPAT significa monocromatico: nessun rilevamento arbitrario del colore.
- Debayer bilineare calcolato una volta, preservando i campioni misurati. RGB lineare su cache privata; campioni sorgente lineari immutati in memoria. SCNR average-neutral 100% solo sui dati a colori, prima dello stretch.
- Livelli dai percentili 0,1% / 99,95%, LUT 4096 e asinh, come nel riferimento HTML. Le medesime LUT e SCNR vengono usate per anteprima e JPG.
- Anteprima fino a 1500 pixel sul lato lungo; esportazione indipendente da anteprima, zoom e pan.
- ROWORDER=TOP-DOWN rispettato; altrimenti le righe FITS sono visualizzate dal basso verso l'alto, secondo la convenzione FITS. Il debayer usa sempre le coordinate del sensore prima del ribaltamento.
- Limite esplicito 32 MP. Cubi, HDU di estensione, FITS compressi/tiled, tabelle, pattern CFA diversi e BITPIX 64 intero sono rifiutati con messaggio. Per 9 MP: circa 36 MB di sorgente, 27 MB massimi di anteprima colore, cache RGB da 108 MB su disco, bitmap JPG temporanea da 36 MB; picchi dipendono dalla VM e dalle bitmap precedenti in raccolta. Esportazioni più grandi dipendono dalla memoria del dispositivo.

## Compilazione e test

Richiede JDK 17, Android SDK 36 e Gradle 8.11.1. Il workflow crea il wrapper Gradle standard prima di compilare. In locale, con Gradle installato:

```sh
gradle wrapper --gradle-version 8.11.1
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Il modulo `core` non dipende da Android; include test sintetici di header, endianess, scaling, Bayer, SCNR, stretch, orientamento, cache, cancellazione ed errori. I test Android Robolectric verificano JPG reale, risoluzione, regolazioni e transazione MediaStore, inclusa la rimozione delle righe pending se il salvataggio fallisce.

Ogni push/PR attiva `.github/workflows/android.yml`. Un run completato con successo pubblica l'artifact **FITS-Explorer-debug** (APK debug installabile) e i report. Il workflow può anche essere avviato manualmente.

## Stato di verifica

Progetto implementato; **compilazione e test non ancora eseguiti**. Nell'ambiente di preparazione non sono disponibili Gradle/Android SDK e il proxy di rete non è raggiungibile. La connessione GitHub rifiuta le scritture con `403 Resource not accessible by integration`: finché non viene ripristinata non è possibile pubblicare il codice, avviare Actions o fornire un APK verificato.

Prima di considerare pronto il rilascio, verificare il run Actions e provare almeno un vero light ASIAIR ASI533MC Pro sul dispositivo. Non sono stati forniti FITS reali di riferimento per verificare colori e orientamento sul sensore effettivo.

## Architettura

`core/FitsReader`: parser stream; `core/Processing`: debayer/cache/LUT; `ExplorerViewModel`: operazioni cancellabili fuori dalla UI, sessione serializzata e stato; `MainActivity`: Compose Material 3; `JpegExporter`: bitmap full size e MediaStore con IS_PENDING/rollback.

Il riferimento web e le differenze intenzionali sono descritti in `docs/reference-analysis.md`.
